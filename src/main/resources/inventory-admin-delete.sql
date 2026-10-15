CREATE OR REPLACE FUNCTION erp_inventory_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7821348);
    IF TG_OP='DELETE' THEN
        IF current_setting('erp.admin_inventory_delete',true) IS DISTINCT FROM 'on' THEN
            RAISE EXCEPTION 'STOCK: Chỉ Admin được xóa tồn kho qua ứng dụng.' USING ERRCODE='23514';
        END IF;
        IF OLD.reserved_quantity <> 0 OR OLD.quantity <> 0 THEN
            RAISE EXCEPTION 'STOCK: Phải giải phóng giữ chỗ và ghi điều chỉnh tồn trước khi xóa.' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
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
