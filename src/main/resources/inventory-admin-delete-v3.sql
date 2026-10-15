CREATE OR REPLACE FUNCTION erp_inventory_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF TG_OP = 'DELETE' THEN
        IF OLD.item_id IS NOT NULL AND OLD.quantity = 0 AND OLD.reserved_quantity = 0
                AND EXISTS (SELECT 1 FROM items WHERE id = OLD.item_id AND item_category <> 'INVENTORY') THEN
            RETURN OLD;
        END IF;
        IF current_setting('erp.admin_inventory_delete',true) = 'on'
                AND OLD.quantity = 0 AND OLD.reserved_quantity = 0 THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'STOCK: Chỉ Admin được xóa qua thao tác điều chỉnh tồn kho.' USING ERRCODE='23514';
    END IF;
    IF NEW.item_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM items WHERE id = NEW.item_id AND item_category = 'INVENTORY'
    ) AND NOT (
        TG_OP = 'UPDATE'
        AND current_setting('erp.admin_inventory_delete',true) = 'on'
        AND NEW.item_id IS NOT DISTINCT FROM OLD.item_id
        AND NEW.product_id IS NOT DISTINCT FROM OLD.product_id
        AND NEW.location_id IS NOT DISTINCT FROM OLD.location_id
        AND NEW.quantity <= OLD.quantity
        AND NEW.reserved_quantity <= OLD.reserved_quantity
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

