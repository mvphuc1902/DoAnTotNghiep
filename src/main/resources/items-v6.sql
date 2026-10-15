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
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM production_orders WHERE item_id = $1)'
            INTO referenced USING OLD.id;
        IF referenced THEN
            RAISE EXCEPTION 'STOCK: Không thể đổi Type vì Item đã có lệnh sản xuất.' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
