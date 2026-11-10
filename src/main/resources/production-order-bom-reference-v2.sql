ALTER TABLE production_orders RENAME COLUMN item_id TO bom_parent_item_id;

CREATE OR REPLACE FUNCTION erp_item_category_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE referenced boolean;
BEGIN
    IF NEW.item_category IS NOT DISTINCT FROM OLD.item_category THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(7821348);
    IF EXISTS (SELECT 1 FROM inventory
               WHERE item_id = OLD.id AND (quantity <> 0 OR reserved_quantity <> 0))
       OR EXISTS (SELECT 1 FROM erp_stock_lines
                  WHERE item_id = OLD.id AND applied_quantity <> 0) THEN
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
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM production_orders WHERE bom_parent_item_id = $1)'
            INTO referenced USING OLD.id;
        IF referenced THEN
            RAISE EXCEPTION 'STOCK: Không thể đổi Type vì Item đã có lệnh sản xuất.' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION erp_item_type_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF NEW.item_type='PURCHASED' AND OLD.item_type='MANUFACTURED' AND (
        EXISTS(SELECT 1 FROM item_bom WHERE parent_item_id=NEW.id)
        OR EXISTS(SELECT 1 FROM production_orders WHERE bom_parent_item_id=NEW.id AND status='DRAFT')) THEN
        RAISE EXCEPTION 'STOCK: Item còn BOM hoặc lệnh sản xuất nháp; không được đổi sang hàng mua sẵn.' USING ERRCODE='23514';
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
                   AND item_type='MANUFACTURED' AND NOT coalesce(blocked,false)) THEN
        RAISE EXCEPTION 'STOCK: Lệnh sản xuất phải chọn BOM có Item sản xuất còn hoạt động.' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.status='COMPLETED' THEN
        IF NOT EXISTS(SELECT 1 FROM item_bom WHERE parent_item_id=NEW.bom_parent_item_id) THEN
            RAISE EXCEPTION 'STOCK: Chưa có định mức BOM cho Item này.' USING ERRCODE='23514';
        END IF;
        FOR component IN SELECT b.*,i.blocked FROM item_bom b JOIN items i ON i.id=b.component_item_id
            WHERE b.parent_item_id=NEW.bom_parent_item_id ORDER BY b.component_item_id LOOP
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
