-- Applied once, transactionally. Existing documents are NOT posted again.
ALTER TABLE sales_order_lines ALTER COLUMN quantity TYPE numeric(18,3);
ALTER TABLE items ADD COLUMN IF NOT EXISTS quantity numeric(18,3) NOT NULL DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS item_type varchar(20) NOT NULL DEFAULT 'PURCHASED'
    CHECK (item_type IN ('PURCHASED','MANUFACTURED'));
ALTER TABLE inventory ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE inventory ADD COLUMN item_id bigint REFERENCES items(id);
ALTER TABLE inventory ADD CONSTRAINT chk_inventory_subject CHECK ((item_id IS NULL) <> (product_id IS NULL));
CREATE UNIQUE INDEX uq_inventory_item_location ON inventory(item_id,location_id);
ALTER TABLE inventory_ledger ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE inventory_ledger ADD COLUMN item_id bigint REFERENCES items(id);

CREATE TABLE erp_stock_lines (
    source_table varchar(50) NOT NULL,
    source_id bigint NOT NULL,
    item_id bigint NOT NULL REFERENCES items(id),
    location_id bigint NOT NULL REFERENCES locations(id),
    applied_quantity numeric(18,3) NOT NULL,
    PRIMARY KEY (source_table, source_id)
);

CREATE OR REPLACE FUNCTION erp_default_location() RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE result bigint;
BEGIN
    SELECT id INTO result FROM locations ORDER BY (code='MAIN') DESC, id LIMIT 1;
    IF result IS NULL THEN
        RAISE EXCEPTION 'STOCK: Vui lòng tạo kho trước khi tạo vật tư.' USING ERRCODE='23514';
    END IF;
    RETURN result;
END $$;

CREATE OR REPLACE FUNCTION erp_item_inventory() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    INSERT INTO inventory(item_id,location_id,quantity,average_cost)
    VALUES (NEW.id,erp_default_location(),0,coalesce(NEW.unit_cost,0));
    RETURN NEW;
END $$;

CREATE TRIGGER erp_item_inventory AFTER INSERT ON items
FOR EACH ROW EXECUTE FUNCTION erp_item_inventory();

-- Old product-based inventory is preserved separately; never guess an Item association.
INSERT INTO inventory(item_id,location_id,quantity,average_cost)
SELECT id,erp_default_location(),0,coalesce(unit_cost,0) FROM items;

CREATE OR REPLACE FUNCTION erp_inventory_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'STOCK: Không được xóa tồn kho; hãy lập điều chỉnh số lượng.' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.item_id IS DISTINCT FROM OLD.item_id OR NEW.location_id<>OLD.location_id) THEN
        RAISE EXCEPTION 'STOCK: Không được đổi sản phẩm/kho của bản ghi tồn kho.' USING ERRCODE='23514';
    END IF;
    IF NEW.quantity<0 OR NEW.reserved_quantity<0 OR NEW.quantity<NEW.reserved_quantity THEN
        RAISE EXCEPTION 'STOCK: Không đủ tồn kho khả dụng.' USING ERRCODE='23514';
    END IF;
    NEW.updated_at=CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;
CREATE TRIGGER erp_inventory_guard BEFORE INSERT OR UPDATE OR DELETE ON inventory
FOR EACH ROW EXECUTE FUNCTION erp_inventory_guard();

CREATE OR REPLACE FUNCTION erp_inventory_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE total numeric; difference numeric;
BEGIN
    IF NEW.item_id IS NOT NULL THEN
        SELECT coalesce(sum(quantity),0) INTO total FROM inventory WHERE item_id=NEW.item_id;
        UPDATE items SET quantity=total WHERE id=NEW.item_id;
    ELSE
        SELECT coalesce(sum(quantity),0) INTO total FROM inventory WHERE product_id=NEW.product_id;
        UPDATE product SET quantity=total WHERE id=NEW.product_id;
    END IF;
    difference=NEW.quantity-CASE WHEN TG_OP='INSERT' THEN 0 ELSE OLD.quantity END;
    IF difference<>0 THEN
        INSERT INTO inventory_ledger(entry_no,product_id,item_id,location_id,document_type,document_id,
            quantity_in,quantity_out,unit_cost,balance_quantity,description)
        VALUES ('AUTO-'||gen_random_uuid(),NEW.product_id,NEW.item_id,NEW.location_id,
            coalesce(nullif(current_setting('erp.stock_source',true),''),'ADJUSTMENT'),
            nullif(current_setting('erp.stock_document',true),'')::bigint,
            greatest(difference,0),greatest(-difference,0),NEW.average_cost,NEW.quantity,
            'Tự động đồng bộ tồn kho');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER erp_inventory_total AFTER INSERT OR UPDATE ON inventory
FOR EACH ROW EXECUTE FUNCTION erp_inventory_total();

CREATE OR REPLACE FUNCTION erp_stock_change(pid bigint, lid bigint, change numeric, source text, doc bigint)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF change=0 THEN RETURN; END IF;
    PERFORM pg_advisory_xact_lock(7821348);
    PERFORM set_config('erp.stock_source',source,true);
    PERFORM set_config('erp.stock_document',doc::text,true);
    INSERT INTO inventory(item_id,location_id,quantity,average_cost)
    SELECT pid,lid,0,coalesce(unit_cost,0) FROM items WHERE id=pid
    ON CONFLICT (item_id,location_id) DO NOTHING;
    UPDATE inventory SET quantity=quantity+change WHERE item_id=pid AND location_id=lid;
    PERFORM set_config('erp.stock_source','',true);
    PERFORM set_config('erp.stock_document','',true);
END $$;

CREATE OR REPLACE FUNCTION erp_post_stock_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE saved erp_stock_lines%ROWTYPE; rowdata jsonb; pid bigint; lid bigint; amount numeric;
        parent bigint; state text; warehouse_code text; sourceid bigint; tracked boolean;
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    sourceid=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
    SELECT * INTO saved FROM erp_stock_lines WHERE source_table=TG_TABLE_NAME AND source_id=sourceid FOR UPDATE;
    tracked=FOUND;
    IF TG_OP<>'INSERT' AND NOT tracked THEN
        -- Historical documents have unknown posting history: keep their original stock intact.
        IF TG_OP='UPDATE' AND
           (to_jsonb(NEW)-ARRAY['description','unit_price','unit_of_measure','discount_percent','discount_amount','vat_percent','vat_amount','line_amount','quantity_shipped','quantity_invoiced'])
           IS NOT DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['description','unit_price','unit_of_measure','discount_percent','discount_amount','vat_percent','vat_amount','line_amount','quantity_shipped','quantity_invoiced']) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'STOCK: Chứng từ cũ chưa có lịch sử tồn kho tự động; không được sửa số lượng hoặc xóa.' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN
        PERFORM erp_stock_change(saved.item_id,saved.location_id,-saved.applied_quantity,TG_TABLE_NAME,sourceid);
        DELETE FROM erp_stock_lines WHERE source_table=TG_TABLE_NAME AND source_id=sourceid;
        RETURN OLD;
    END IF;
    rowdata=to_jsonb(NEW);
    amount=(rowdata->>'quantity')::numeric;
    IF amount IS NULL OR amount<=0 OR amount<>round(amount,3) THEN
        RAISE EXCEPTION 'STOCK: Số lượng phải lớn hơn 0, tối đa 3 chữ số thập phân.' USING ERRCODE='23514';
    END IF;
    pid=(rowdata->>'item_id')::bigint;
    IF NOT EXISTS (SELECT 1 FROM items WHERE id=pid AND NOT coalesce(blocked,false)) THEN
        RAISE EXCEPTION 'STOCK: Vật tư không tồn tại hoặc đang bị khóa.' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='purchase_receipt_lines' THEN
        parent=(rowdata->>'receipt_id')::bigint;
        SELECT coalesce(nullif(rowdata->>'location_code',''),nullif(location_code,'')) INTO warehouse_code
        FROM purchase_receipts WHERE id=parent FOR UPDATE;
        IF warehouse_code IS NULL THEN lid=erp_default_location();
        ELSE SELECT id INTO lid FROM locations WHERE locations.code=warehouse_code; END IF;
    ELSE
        parent=coalesce(rowdata->>'sales_order_id',rowdata->>'sales_header_id')::bigint;
        SELECT coalesce((rowdata->>'location_id')::bigint,location_id,erp_default_location()),status
        INTO lid,state FROM sales_orders WHERE id=parent FOR UPDATE;
        amount=CASE WHEN state='CANCELLED' THEN 0 ELSE -amount END;
    END IF;
    IF lid IS NULL THEN RAISE EXCEPTION 'STOCK: Kho không tồn tại.' USING ERRCODE='23514'; END IF;
    IF tracked AND saved.item_id=pid AND saved.location_id=lid THEN
        PERFORM erp_stock_change(pid,lid,amount-saved.applied_quantity,TG_TABLE_NAME,sourceid);
    ELSE
        IF tracked THEN
            PERFORM erp_stock_change(saved.item_id,saved.location_id,-saved.applied_quantity,TG_TABLE_NAME,sourceid);
        END IF;
        PERFORM erp_stock_change(pid,lid,amount,TG_TABLE_NAME,sourceid);
    END IF;
    INSERT INTO erp_stock_lines VALUES(TG_TABLE_NAME,sourceid,pid,lid,amount)
    ON CONFLICT(source_table,source_id) DO UPDATE
    SET item_id=EXCLUDED.item_id,location_id=EXCLUDED.location_id,applied_quantity=EXCLUDED.applied_quantity;
    RETURN NEW;
END $$;

CREATE TRIGGER erp_post_stock_line AFTER INSERT OR UPDATE OR DELETE ON sales_order_lines
FOR EACH ROW EXECUTE FUNCTION erp_post_stock_line();
CREATE TRIGGER erp_post_stock_line AFTER INSERT OR UPDATE OR DELETE ON sales_lines
FOR EACH ROW EXECUTE FUNCTION erp_post_stock_line();
CREATE TRIGGER erp_post_stock_line AFTER INSERT OR UPDATE OR DELETE ON purchase_receipt_lines
FOR EACH ROW EXECUTE FUNCTION erp_post_stock_line();

CREATE OR REPLACE FUNCTION erp_stock_header() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF TG_TABLE_NAME='sales_orders' THEN
        IF NEW.location_id IS NOT DISTINCT FROM OLD.location_id AND NEW.status=OLD.status THEN RETURN NEW; END IF;
        IF EXISTS (SELECT 1 FROM sales_order_lines l WHERE sales_order_id=NEW.id AND NOT EXISTS
            (SELECT 1 FROM erp_stock_lines s WHERE s.source_table='sales_order_lines' AND s.source_id=l.id))
            OR EXISTS (SELECT 1 FROM sales_lines l WHERE sales_header_id=NEW.id AND NOT EXISTS
            (SELECT 1 FROM erp_stock_lines s WHERE s.source_table='sales_lines' AND s.source_id=l.id)) THEN
            RAISE EXCEPTION 'STOCK: Đơn cũ chưa đối soát tồn kho; không được đổi kho hoặc trạng thái.' USING ERRCODE='23514';
        END IF;
        UPDATE sales_order_lines SET quantity=quantity WHERE sales_order_id=NEW.id;
        UPDATE sales_lines SET quantity=quantity WHERE sales_header_id=NEW.id;
    ELSE
        IF NEW.location_code IS NOT DISTINCT FROM OLD.location_code THEN RETURN NEW; END IF;
        IF EXISTS (SELECT 1 FROM purchase_receipt_lines l WHERE receipt_id=NEW.id AND NOT EXISTS
            (SELECT 1 FROM erp_stock_lines s WHERE s.source_table='purchase_receipt_lines' AND s.source_id=l.id)) THEN
            RAISE EXCEPTION 'STOCK: Phiếu nhập cũ chưa đối soát tồn kho; không được đổi kho.' USING ERRCODE='23514';
        END IF;
        UPDATE purchase_receipt_lines SET quantity=quantity WHERE receipt_id=NEW.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER erp_stock_header AFTER UPDATE ON sales_orders FOR EACH ROW EXECUTE FUNCTION erp_stock_header();
CREATE TRIGGER erp_stock_header AFTER UPDATE ON purchase_receipts FOR EACH ROW EXECUTE FUNCTION erp_stock_header();

CREATE TABLE item_bom (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    parent_item_id bigint NOT NULL REFERENCES items(id),
    component_item_id bigint NOT NULL REFERENCES items(id),
    quantity numeric(18,3) NOT NULL CHECK (quantity>0),
    UNIQUE(parent_item_id,component_item_id),
    CHECK (parent_item_id<>component_item_id)
);
CREATE TABLE production_orders (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    production_no varchar(50) NOT NULL UNIQUE,
    item_id bigint NOT NULL REFERENCES items(id),
    location_id bigint NOT NULL REFERENCES locations(id),
    quantity numeric(18,3) NOT NULL CHECK (quantity>0),
    status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','COMPLETED','CANCELLED')),
    notes varchar(500),
    completed_at timestamp,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE production_components (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    production_order_id bigint NOT NULL REFERENCES production_orders(id),
    component_item_id bigint NOT NULL REFERENCES items(id),
    quantity_per_unit numeric(18,3) NOT NULL,
    consumed_quantity numeric(18,3) NOT NULL,
    UNIQUE(production_order_id,component_item_id)
);

CREATE OR REPLACE FUNCTION erp_bom_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF NOT EXISTS (SELECT 1 FROM items WHERE id=NEW.parent_item_id AND item_type='MANUFACTURED') THEN
        RAISE EXCEPTION 'STOCK: Chỉ Item sản xuất mới được khai báo BOM.' USING ERRCODE='23514';
    END IF;
    IF NEW.parent_item_id=NEW.component_item_id OR EXISTS (
        WITH RECURSIVE components(id) AS (
            SELECT NEW.component_item_id
            UNION
            SELECT b.component_item_id FROM item_bom b JOIN components c ON b.parent_item_id=c.id
            WHERE b.id<>NEW.id
        ) SELECT 1 FROM components WHERE id=NEW.parent_item_id
    ) THEN
        RAISE EXCEPTION 'STOCK: BOM không được chứa vòng lặp linh kiện.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER erp_bom_guard BEFORE INSERT OR UPDATE ON item_bom FOR EACH ROW EXECUTE FUNCTION erp_bom_guard();

CREATE OR REPLACE FUNCTION erp_item_type_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF NEW.item_type='PURCHASED' AND OLD.item_type='MANUFACTURED' AND (
        EXISTS(SELECT 1 FROM item_bom WHERE parent_item_id=NEW.id)
        OR EXISTS(SELECT 1 FROM production_orders WHERE item_id=NEW.id AND status='DRAFT')) THEN
        RAISE EXCEPTION 'STOCK: Item còn BOM hoặc lệnh sản xuất nháp; không được đổi sang hàng mua sẵn.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER erp_item_type_guard BEFORE UPDATE OF item_type ON items FOR EACH ROW EXECUTE FUNCTION erp_item_type_guard();

CREATE OR REPLACE FUNCTION erp_complete_production() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE component record; required numeric;
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF TG_OP='DELETE' THEN
        IF OLD.status<>'DRAFT' THEN RAISE EXCEPTION 'STOCK: Chỉ được xóa lệnh sản xuất nháp.' USING ERRCODE='23514'; END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='INSERT' AND NEW.status<>'DRAFT' THEN
        RAISE EXCEPTION 'STOCK: Tạo lệnh nháp trước khi hoàn tất sản xuất.' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND OLD.status<>'DRAFT' THEN
        IF (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'STOCK: Lệnh đã hoàn tất hoặc hủy không được sửa.' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM items WHERE id=NEW.item_id AND item_type='MANUFACTURED' AND NOT coalesce(blocked,false)) THEN
        RAISE EXCEPTION 'STOCK: Lệnh sản xuất phải chọn Item có production và không bị khóa.' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.status='COMPLETED' THEN
        IF NOT EXISTS(SELECT 1 FROM item_bom WHERE parent_item_id=NEW.item_id) THEN
            RAISE EXCEPTION 'STOCK: Chưa có định mức BOM cho Item này.' USING ERRCODE='23514';
        END IF;
        FOR component IN SELECT b.*,i.blocked FROM item_bom b JOIN items i ON i.id=b.component_item_id
            WHERE b.parent_item_id=NEW.item_id ORDER BY b.component_item_id LOOP
            IF coalesce(component.blocked,false) THEN
                RAISE EXCEPTION 'STOCK: Linh kiện đang bị khóa.' USING ERRCODE='23514';
            END IF;
            required=component.quantity*NEW.quantity;
            IF required<>round(required,3) THEN
                RAISE EXCEPTION 'STOCK: Số lượng linh kiện tính theo BOM vượt độ chính xác 3 chữ số thập phân.' USING ERRCODE='23514';
            END IF;
            PERFORM erp_stock_change(component.component_item_id,NEW.location_id,-required,'PRODUCTION',NEW.id);
            INSERT INTO production_components(production_order_id,component_item_id,quantity_per_unit,consumed_quantity)
            VALUES(NEW.id,component.component_item_id,component.quantity,required);
        END LOOP;
        PERFORM erp_stock_change(NEW.item_id,NEW.location_id,NEW.quantity,'PRODUCTION',NEW.id);
        NEW.completed_at=CURRENT_TIMESTAMP;
    END IF;
    NEW.updated_at=CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;
CREATE TRIGGER erp_complete_production BEFORE INSERT OR UPDATE OR DELETE ON production_orders
FOR EACH ROW EXECUTE FUNCTION erp_complete_production();
