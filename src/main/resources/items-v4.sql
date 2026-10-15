ALTER TABLE items
    ADD COLUMN IF NOT EXISTS item_category varchar(20) NOT NULL DEFAULT 'INVENTORY',
    ALTER COLUMN quantity TYPE numeric(18,3) USING coalesce(quantity, 0)::numeric(18,3),
    ALTER COLUMN quantity SET DEFAULT 0,
    ALTER COLUMN quantity SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'items'::regclass AND conname = 'items_item_category_check'
    ) THEN
        ALTER TABLE items ADD CONSTRAINT items_item_category_check
            CHECK (item_category IN ('INVENTORY', 'SERVICE', 'NON_INVENTORY'));
    END IF;
END $$;

CREATE OR REPLACE FUNCTION erp_item_inventory() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.item_category = 'INVENTORY' THEN
        INSERT INTO inventory(item_id, location_id, quantity, average_cost)
        VALUES (NEW.id, erp_default_location(), 0, coalesce(NEW.unit_cost, 0));
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION erp_inventory_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF TG_OP = 'DELETE' THEN
        IF OLD.item_id IS NOT NULL AND OLD.quantity = 0 AND OLD.reserved_quantity = 0
                AND EXISTS (SELECT 1 FROM items WHERE id = OLD.item_id AND item_category <> 'INVENTORY') THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'STOCK: Không được xóa tồn kho; hãy lập điều chỉnh số lượng.' USING ERRCODE='23514';
    END IF;
    IF NEW.item_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM items WHERE id = NEW.item_id AND item_category = 'INVENTORY'
    ) THEN
        RAISE EXCEPTION 'STOCK: Chỉ Item loại Inventory mới được có tồn kho.' USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.item_id IS DISTINCT FROM OLD.item_id OR NEW.location_id <> OLD.location_id) THEN
        RAISE EXCEPTION 'STOCK: Không được đổi sản phẩm/kho của bản ghi tồn kho.' USING ERRCODE='23514';
    END IF;
    IF NEW.quantity < 0 OR NEW.reserved_quantity < 0 OR NEW.quantity < NEW.reserved_quantity THEN
        RAISE EXCEPTION 'STOCK: Không đủ tồn kho khả dụng.' USING ERRCODE='23514';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION erp_stock_change(pid bigint, lid bigint, change numeric, source text, doc bigint)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF change = 0 THEN RETURN; END IF;
    IF NOT EXISTS (SELECT 1 FROM items WHERE id = pid AND item_category = 'INVENTORY') THEN
        RAISE EXCEPTION 'STOCK: Chỉ Item loại Inventory mới được thay đổi tồn kho.' USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock(7821348);
    PERFORM set_config('erp.stock_source', source, true);
    PERFORM set_config('erp.stock_document', doc::text, true);
    INSERT INTO inventory(item_id, location_id, quantity, average_cost)
    SELECT pid, lid, 0, coalesce(unit_cost, 0) FROM items WHERE id = pid
    ON CONFLICT (item_id, location_id) DO NOTHING;
    UPDATE inventory SET quantity = quantity + change WHERE item_id = pid AND location_id = lid;
    PERFORM set_config('erp.stock_source', '', true);
    PERFORM set_config('erp.stock_document', '', true);
END $$;

CREATE OR REPLACE FUNCTION erp_post_stock_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE saved erp_stock_lines%ROWTYPE; rowdata jsonb; pid bigint; lid bigint; amount numeric;
        parent bigint; state text; warehouse_code text; sourceid bigint; tracked boolean;
        item_category text;
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    sourceid = CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    SELECT * INTO saved FROM erp_stock_lines
    WHERE source_table = TG_TABLE_NAME AND source_id = sourceid FOR UPDATE;
    tracked = FOUND;
    IF TG_OP <> 'INSERT' AND NOT tracked THEN
        IF TG_OP = 'UPDATE' AND
           (to_jsonb(NEW)-ARRAY['description','unit_price','unit_of_measure','discount_percent','discount_amount','vat_percent','vat_amount','line_amount','quantity_shipped','quantity_invoiced'])
           IS NOT DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['description','unit_price','unit_of_measure','discount_percent','discount_amount','vat_percent','vat_amount','line_amount','quantity_shipped','quantity_invoiced']) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'STOCK: Chứng từ cũ chưa có lịch sử tồn kho tự động; không được sửa số lượng hoặc xóa.' USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        PERFORM erp_stock_change(saved.item_id, saved.location_id, -saved.applied_quantity, TG_TABLE_NAME, sourceid);
        DELETE FROM erp_stock_lines WHERE source_table = TG_TABLE_NAME AND source_id = sourceid;
        RETURN OLD;
    END IF;
    rowdata = to_jsonb(NEW);
    amount = (rowdata->>'quantity')::numeric;
    IF amount IS NULL OR amount <= 0 OR amount <> round(amount, 3) THEN
        RAISE EXCEPTION 'STOCK: Số lượng phải lớn hơn 0, tối đa 3 chữ số thập phân.' USING ERRCODE='23514';
    END IF;
    pid = (rowdata->>'item_id')::bigint;
    SELECT i.item_category INTO item_category FROM items i
    WHERE i.id = pid AND NOT coalesce(i.blocked, false);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'STOCK: Vật tư không tồn tại hoặc đang bị khóa.' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME = 'purchase_receipt_lines' THEN
        IF item_category <> 'INVENTORY' THEN
            RAISE EXCEPTION 'STOCK: Chỉ Item loại Inventory mới được nhập kho.' USING ERRCODE='23514';
        END IF;
        parent = (rowdata->>'receipt_id')::bigint;
        SELECT coalesce(nullif(rowdata->>'location_code',''), nullif(location_code,''))
        INTO warehouse_code FROM purchase_receipts WHERE id = parent FOR UPDATE;
        IF warehouse_code IS NULL THEN
            lid = erp_default_location();
        ELSE
            SELECT id INTO lid FROM locations WHERE locations.code = warehouse_code;
        END IF;
    ELSE
        parent = coalesce(rowdata->>'sales_order_id', rowdata->>'sales_header_id')::bigint;
        SELECT coalesce((rowdata->>'location_id')::bigint, location_id, erp_default_location()), status
        INTO lid, state FROM sales_orders WHERE id = parent FOR UPDATE;
        amount = CASE WHEN state = 'CANCELLED' OR item_category <> 'INVENTORY' THEN 0 ELSE -amount END;
    END IF;
    IF lid IS NULL THEN RAISE EXCEPTION 'STOCK: Kho không tồn tại.' USING ERRCODE='23514'; END IF;
    IF tracked AND saved.item_id = pid AND saved.location_id = lid THEN
        PERFORM erp_stock_change(pid, lid, amount - saved.applied_quantity, TG_TABLE_NAME, sourceid);
    ELSE
        IF tracked THEN
            PERFORM erp_stock_change(saved.item_id, saved.location_id, -saved.applied_quantity, TG_TABLE_NAME, sourceid);
        END IF;
        PERFORM erp_stock_change(pid, lid, amount, TG_TABLE_NAME, sourceid);
    END IF;
    INSERT INTO erp_stock_lines VALUES (TG_TABLE_NAME, sourceid, pid, lid, amount)
    ON CONFLICT (source_table, source_id) DO UPDATE
    SET item_id = EXCLUDED.item_id, location_id = EXCLUDED.location_id, applied_quantity = EXCLUDED.applied_quantity;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION erp_item_category_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE referenced boolean;
BEGIN
    IF NEW.item_category IS NOT DISTINCT FROM OLD.item_category THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(7821348);
    IF EXISTS (SELECT 1 FROM inventory WHERE item_id = OLD.id AND (quantity <> 0 OR reserved_quantity <> 0))
       OR EXISTS (SELECT 1 FROM erp_stock_lines WHERE item_id = OLD.id AND applied_quantity <> 0)
       THEN
        RAISE EXCEPTION 'STOCK: Không thể đổi Type vì Item đã có tồn kho hoặc lịch sử nghiệp vụ.' USING ERRCODE='23514';
    END IF;
    IF to_regclass('public.item_bom') IS NOT NULL THEN
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM item_bom WHERE parent_item_id = $1 OR component_item_id = $1)'
            INTO referenced USING OLD.id;
        IF referenced THEN
            RAISE EXCEPTION 'STOCK: Không thể đổi Type vì Item đang được sử dụng trong BOM.' USING ERRCODE='23514';
        END IF;
    END IF;
    IF to_regclass('public.production_orders') IS NOT NULL THEN
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM production_orders WHERE item_id = $1)'
            INTO referenced USING OLD.id;
        IF referenced THEN
            RAISE EXCEPTION 'STOCK: Không thể đổi Type vì Item đã có lệnh sản xuất.' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION erp_item_category_inventory() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.item_category = 'INVENTORY' THEN
        INSERT INTO inventory(item_id, location_id, quantity, average_cost)
        VALUES (NEW.id, erp_default_location(), 0, coalesce(NEW.unit_cost, 0))
        ON CONFLICT (item_id, location_id) DO NOTHING;
    ELSE
        DELETE FROM inventory WHERE item_id = NEW.id AND quantity = 0 AND reserved_quantity = 0;
        UPDATE items SET quantity = 0 WHERE id = NEW.id;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS erp_item_category_guard ON items;
CREATE TRIGGER erp_item_category_guard BEFORE UPDATE OF item_category ON items
FOR EACH ROW EXECUTE FUNCTION erp_item_category_guard();
DROP TRIGGER IF EXISTS erp_item_category_inventory ON items;
CREATE TRIGGER erp_item_category_inventory AFTER UPDATE OF item_category ON items
FOR EACH ROW EXECUTE FUNCTION erp_item_category_inventory();

CREATE OR REPLACE FUNCTION erp_bom_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF NOT EXISTS (SELECT 1 FROM items WHERE id = NEW.parent_item_id
            AND item_type = 'MANUFACTURED' AND item_category = 'INVENTORY') THEN
        RAISE EXCEPTION 'STOCK: BOM cần Item cha loại Inventory có Origin Manufactured.' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM items WHERE id = NEW.component_item_id AND item_category = 'INVENTORY') THEN
        RAISE EXCEPTION 'STOCK: Linh kiện BOM phải thuộc Type Inventory.' USING ERRCODE='23514';
    END IF;
    IF NEW.parent_item_id = NEW.component_item_id OR EXISTS (
        WITH RECURSIVE components(id) AS (
            SELECT NEW.component_item_id
            UNION
            SELECT b.component_item_id FROM item_bom b JOIN components c ON b.parent_item_id = c.id
            WHERE b.id <> NEW.id
        ) SELECT 1 FROM components WHERE id = NEW.parent_item_id
    ) THEN
        RAISE EXCEPTION 'STOCK: BOM không được chứa vòng lặp linh kiện.' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION erp_complete_production() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE component record; required numeric;
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN RAISE EXCEPTION 'STOCK: Chỉ được xóa lệnh sản xuất nháp.' USING ERRCODE='23514'; END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'STOCK: Tạo lệnh nháp trước khi hoàn tất sản xuất.' USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status <> 'DRAFT' THEN
        IF (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'STOCK: Lệnh đã hoàn tất hoặc hủy không được sửa.' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM items WHERE id = NEW.item_id AND item_type = 'MANUFACTURED'
            AND item_category = 'INVENTORY' AND NOT coalesce(blocked, false)) THEN
        RAISE EXCEPTION 'STOCK: Lệnh sản xuất cần Item loại Inventory có Origin Manufactured và không bị khóa.' USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.status = 'COMPLETED' THEN
        IF NOT EXISTS (SELECT 1 FROM item_bom WHERE parent_item_id = NEW.item_id) THEN
            RAISE EXCEPTION 'STOCK: Chưa có định mức BOM cho Item này.' USING ERRCODE='23514';
        END IF;
        FOR component IN SELECT b.*, i.blocked FROM item_bom b JOIN items i ON i.id = b.component_item_id
            WHERE b.parent_item_id = NEW.item_id ORDER BY b.component_item_id LOOP
            IF coalesce(component.blocked, false) THEN
                RAISE EXCEPTION 'STOCK: Linh kiện đang bị khóa.' USING ERRCODE='23514';
            END IF;
            required = component.quantity * NEW.quantity;
            IF required <> round(required, 3) THEN
                RAISE EXCEPTION 'STOCK: Số lượng linh kiện tính theo BOM vượt độ chính xác 3 chữ số thập phân.' USING ERRCODE='23514';
            END IF;
            PERFORM erp_stock_change(component.component_item_id, NEW.location_id, -required, 'PRODUCTION', NEW.id);
            INSERT INTO production_components(production_order_id, component_item_id, quantity_per_unit, consumed_quantity)
            VALUES (NEW.id, component.component_item_id, component.quantity, required);
        END LOOP;
        PERFORM erp_stock_change(NEW.item_id, NEW.location_id, NEW.quantity, 'PRODUCTION', NEW.id);
        NEW.completed_at = CURRENT_TIMESTAMP;
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;

UPDATE items i SET quantity = coalesce(stock.total, 0)
FROM (SELECT item_id, sum(quantity) AS total FROM inventory WHERE item_id IS NOT NULL GROUP BY item_id) stock
WHERE i.id = stock.item_id;
UPDATE items SET quantity = 0 WHERE item_category <> 'INVENTORY';
