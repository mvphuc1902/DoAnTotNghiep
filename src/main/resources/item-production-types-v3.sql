ALTER TABLE items ALTER COLUMN item_type DROP DEFAULT;
ALTER TABLE items DROP CONSTRAINT IF EXISTS items_item_type_check;

UPDATE items item
SET item_type = 'PRODUCTION_COMPONENT'
WHERE item.item_type = 'PURCHASED'
  AND item.item_category = 'INVENTORY'
  AND EXISTS (SELECT 1 FROM item_bom bom WHERE bom.component_item_id = item.id)
  AND NOT EXISTS (SELECT 1 FROM item_bom bom WHERE bom.parent_item_id = item.id);

UPDATE items SET item_type = 'DEFAULT' WHERE item_type = 'PURCHASED';

ALTER TABLE items ALTER COLUMN item_type SET DEFAULT 'DEFAULT';
ALTER TABLE items ADD CONSTRAINT items_item_type_check
    CHECK (item_type IN ('DEFAULT', 'MANUFACTURED', 'PRODUCTION_COMPONENT'));

CREATE OR REPLACE FUNCTION erp_item_type_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF NEW.item_type IS DISTINCT FROM OLD.item_type THEN
        IF (EXISTS (SELECT 1 FROM item_bom WHERE parent_item_id = OLD.id)
                OR EXISTS (SELECT 1 FROM production_orders WHERE bom_parent_item_id = OLD.id))
           AND NEW.item_type <> 'MANUFACTURED' THEN
            RAISE EXCEPTION 'STOCK: Item có BOM hoặc lệnh sản xuất; không thể đổi khỏi loại Sản xuất.' USING ERRCODE='23514';
        END IF;
        IF EXISTS (SELECT 1 FROM item_bom WHERE component_item_id = OLD.id)
           AND NEW.item_type <> 'PRODUCTION_COMPONENT' THEN
            RAISE EXCEPTION 'STOCK: Item đang được dùng trong BOM; không thể đổi khỏi loại Linh kiện sản xuất.' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION erp_bom_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF NOT EXISTS (SELECT 1 FROM items WHERE id = NEW.parent_item_id
            AND item_type = 'MANUFACTURED' AND item_category = 'INVENTORY' AND NOT coalesce(blocked, false)) THEN
        RAISE EXCEPTION 'STOCK: BOM cần Item cha thuộc loại Sản xuất, Inventory và đang hoạt động.' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM items WHERE id = NEW.component_item_id
            AND item_type = 'PRODUCTION_COMPONENT' AND item_category = 'INVENTORY' AND NOT coalesce(blocked, false)) THEN
        RAISE EXCEPTION 'STOCK: Linh kiện BOM phải là Item loại Linh kiện sản xuất, Inventory và đang hoạt động.' USING ERRCODE='23514';
    END IF;
    IF NEW.parent_item_id = NEW.component_item_id OR EXISTS (
        WITH RECURSIVE components(id) AS (
            SELECT NEW.component_item_id
            UNION
            SELECT bom.component_item_id FROM item_bom bom
            JOIN components chain_node ON bom.parent_item_id = chain_node.id
            WHERE bom.id <> NEW.id
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
    IF NOT EXISTS (SELECT 1 FROM items WHERE id=NEW.bom_parent_item_id
                   AND item_type='MANUFACTURED' AND item_category='INVENTORY' AND NOT coalesce(blocked,false)) THEN
        RAISE EXCEPTION 'STOCK: Lệnh sản xuất phải chọn Item loại Sản xuất còn hoạt động.' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.status='COMPLETED' THEN
        IF NOT EXISTS(SELECT 1 FROM item_bom WHERE parent_item_id=NEW.bom_parent_item_id) THEN
            RAISE EXCEPTION 'STOCK: Chưa có định mức BOM cho Item này.' USING ERRCODE='23514';
        END IF;
        FOR component IN SELECT bom.*,item.blocked FROM item_bom bom
            JOIN items item ON item.id=bom.component_item_id
            WHERE bom.parent_item_id=NEW.bom_parent_item_id ORDER BY bom.component_item_id LOOP
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
        PERFORM erp_stock_change(NEW.bom_parent_item_id,NEW.location_id,NEW.quantity,'PRODUCTION',NEW.id);
        NEW.completed_at=CURRENT_TIMESTAMP;
    END IF;
    NEW.updated_at=CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;
