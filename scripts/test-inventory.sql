-- Run inside a transaction after inventory-schema.sql; caller MUST ROLLBACK.
DO $$
DECLARE raw_id bigint; made_id bigint; sub_id bigint; wh bigint; wh2 bigint;
        receipt bigint; receipt_line bigint; sale bigint; sale_line bigint;
        production bigint; invalid_production bigint; before_qty numeric; ledger_count bigint;
        marker text='TEST-'||substr(gen_random_uuid()::text,1,8);
BEGIN
    wh=erp_default_location();
    INSERT INTO locations(code,name) VALUES(marker,'Test warehouse') RETURNING id INTO wh2;
    INSERT INTO items(item_no,description) VALUES(marker||'-RAW','Raw item') RETURNING id INTO raw_id;
    INSERT INTO items(item_no,description,item_type) VALUES(marker||'-FG','Finished item','MANUFACTURED') RETURNING id INTO made_id;
    INSERT INTO items(item_no,description,item_type) VALUES(marker||'-SUB','Subassembly','MANUFACTURED') RETURNING id INTO sub_id;
    ASSERT (SELECT count(*)=1 FROM inventory WHERE item_id=raw_id), 'Item must create inventory';
    ASSERT (SELECT quantity=0 FROM items WHERE id=raw_id), 'Initial quantity must be zero';
    ASSERT NOT EXISTS(SELECT 1 FROM product WHERE code=marker||'-RAW'), 'Item must not create Product';

    INSERT INTO purchase_receipts(receipt_no,vendor_id,location_code)
    VALUES(marker,(SELECT id FROM vendors ORDER BY id LIMIT 1),(SELECT code FROM locations WHERE id=wh)) RETURNING id INTO receipt;
    INSERT INTO purchase_receipt_lines(receipt_id,line_no,item_id,quantity)
    VALUES(receipt,10000,raw_id,10) RETURNING id INTO receipt_line;
    ASSERT (SELECT quantity=10 FROM items WHERE id=raw_id), 'Receipt adds stock';
    UPDATE purchase_receipt_lines SET quantity=12 WHERE id=receipt_line;
    ASSERT (SELECT quantity=12 FROM items WHERE id=raw_id), 'Receipt update applies difference';
    UPDATE purchase_receipt_lines SET quantity=12 WHERE id=receipt_line;
    ASSERT (SELECT quantity=12 FROM items WHERE id=raw_id), 'Receipt retry must not duplicate';

    INSERT INTO sales_orders(order_no,customer_id,location_id)
    VALUES(marker,(SELECT id FROM customers ORDER BY id LIMIT 1),wh) RETURNING id INTO sale;
    INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,quantity)
    VALUES(sale,10000,raw_id,3) RETURNING id INTO sale_line;
    ASSERT (SELECT quantity=9 FROM items WHERE id=raw_id), 'Sale deducts stock';
    UPDATE sales_order_lines SET quantity=4 WHERE id=sale_line;
    ASSERT (SELECT quantity=8 FROM items WHERE id=raw_id), 'Sale update applies difference';
    UPDATE sales_order_lines SET quantity=4 WHERE id=sale_line;
    ASSERT (SELECT quantity=8 FROM items WHERE id=raw_id), 'Sale retry must not duplicate';
    BEGIN
        UPDATE sales_order_lines SET quantity=100 WHERE id=sale_line;
        RAISE EXCEPTION 'Overselling was allowed';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    ASSERT (SELECT quantity=8 FROM items WHERE id=raw_id), 'Rejected sale must roll back stock';
    ASSERT (SELECT quantity=4 FROM sales_order_lines WHERE id=sale_line), 'Rejected sale must roll back line';
    BEGIN
        UPDATE sales_order_lines SET quantity=0 WHERE id=sale_line;
        RAISE EXCEPTION 'Zero sale was allowed';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    UPDATE sales_orders SET status='CANCELLED' WHERE id=sale;
    ASSERT (SELECT quantity=12 FROM items WHERE id=raw_id), 'Cancel restores stock';
    UPDATE sales_orders SET status='CANCELLED' WHERE id=sale;
    ASSERT (SELECT quantity=12 FROM items WHERE id=raw_id), 'Cancel retry must not duplicate';
    UPDATE sales_orders SET status='OPEN' WHERE id=sale;
    ASSERT (SELECT quantity=8 FROM items WHERE id=raw_id), 'Reopen deducts again';
    DELETE FROM sales_order_lines WHERE id=sale_line;
    ASSERT (SELECT quantity=12 FROM items WHERE id=raw_id), 'Delete restores sale';

    UPDATE purchase_receipts SET location_code=(SELECT code FROM locations WHERE id=wh2) WHERE id=receipt;
    ASSERT (SELECT quantity=12 FROM inventory WHERE item_id=raw_id AND location_id=wh2), 'Receipt moves warehouse';
    ASSERT (SELECT quantity=0 FROM inventory WHERE item_id=raw_id AND location_id=wh), 'Old warehouse restored';
    ASSERT (SELECT quantity=12 FROM items WHERE id=raw_id), 'Item total sums warehouses';
    UPDATE purchase_receipts SET location_code=(SELECT code FROM locations WHERE id=wh) WHERE id=receipt;

    INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(made_id,raw_id,2);
    INSERT INTO production_orders(production_no,item_id,location_id,quantity)
    VALUES(marker,made_id,wh,3) RETURNING id INTO production;
    ASSERT (SELECT quantity=12 FROM items WHERE id=raw_id), 'Draft must not consume';
    UPDATE production_orders SET status='COMPLETED' WHERE id=production;
    ASSERT (SELECT quantity=6 FROM items WHERE id=raw_id), 'Production consumes BOM times quantity';
    ASSERT (SELECT quantity=3 FROM items WHERE id=made_id), 'Production creates finished Items';
    ASSERT (SELECT consumed_quantity=6 FROM production_components WHERE production_order_id=production), 'Consumption snapshot';
    UPDATE production_orders SET status='COMPLETED',updated_at=clock_timestamp() WHERE id=production;
    ASSERT (SELECT quantity=3 FROM items WHERE id=made_id), 'Completion retry must not duplicate';
    BEGIN
        UPDATE production_orders SET quantity=4 WHERE id=production;
        RAISE EXCEPTION 'Completed production was editable';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    UPDATE item_bom SET quantity=3 WHERE parent_item_id=made_id;
    ASSERT (SELECT quantity_per_unit=2 FROM production_components WHERE production_order_id=production), 'Old BOM snapshot must stay unchanged';
    INSERT INTO production_orders(production_no,item_id,location_id,quantity)
    VALUES(marker||'-SHORT',made_id,wh,10) RETURNING id INTO invalid_production;
    BEGIN
        UPDATE production_orders SET status='COMPLETED' WHERE id=invalid_production;
        RAISE EXCEPTION 'Production without components was allowed';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    ASSERT (SELECT status='DRAFT' FROM production_orders WHERE id=invalid_production), 'Shortage preserves draft';
    ASSERT (SELECT quantity=6 FROM items WHERE id=raw_id), 'Shortage rolls back consumption';
    ASSERT NOT EXISTS(SELECT 1 FROM production_components WHERE production_order_id=invalid_production), 'Shortage rolls back snapshot';
    INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(made_id,sub_id,1);
    UPDATE production_orders SET quantity=1 WHERE id=invalid_production;
    BEGIN
        UPDATE production_orders SET status='COMPLETED' WHERE id=invalid_production;
        RAISE EXCEPTION 'Second component shortage was allowed';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    ASSERT (SELECT quantity=6 FROM items WHERE id=raw_id), 'Second component failure rolls back first component';
    ASSERT NOT EXISTS(SELECT 1 FROM production_components WHERE production_order_id=invalid_production), 'Partial snapshots rolled back';
    DELETE FROM item_bom WHERE parent_item_id=made_id AND component_item_id=sub_id;
    BEGIN
        INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(raw_id,made_id,1);
        RAISE EXCEPTION 'Purchased item accepted BOM';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(sub_id,made_id,1);
    BEGIN
        INSERT INTO item_bom(parent_item_id,component_item_id,quantity) VALUES(made_id,sub_id,1);
        RAISE EXCEPTION 'Cyclic BOM allowed';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        DELETE FROM purchase_receipt_lines WHERE id=receipt_line;
        RAISE EXCEPTION 'Consumed receipt was deletable';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    ASSERT (SELECT quantity=6 FROM items WHERE id=raw_id), 'Receipt delete must roll back';
    UPDATE inventory SET reserved_quantity=5 WHERE item_id=raw_id AND location_id=wh;
    BEGIN
        INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,quantity) VALUES(sale,20000,raw_id,2);
        RAISE EXCEPTION 'Sale consumed reserved stock';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    ASSERT (SELECT sum(quantity_in-quantity_out)=6 FROM inventory_ledger WHERE item_id=raw_id), 'Ledger equals inventory';
    INSERT INTO production_orders(production_no,item_id,location_id,quantity)
    VALUES(marker||'-SUB-PROD',sub_id,wh,1) RETURNING id INTO invalid_production;
    UPDATE production_orders SET status='COMPLETED' WHERE id=invalid_production;
    ASSERT (SELECT quantity=1 FROM items WHERE id=sub_id), 'Subassembly can be produced';
    ASSERT (SELECT quantity=2 FROM items WHERE id=made_id), 'Manufactured component is consumed from stock';
    BEGIN
        INSERT INTO production_orders(production_no,item_id,location_id,quantity)
        VALUES(marker||'-RAW-PROD',raw_id,wh,1);
        RAISE EXCEPTION 'Purchased item production was allowed';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE items SET item_type='PURCHASED' WHERE id=made_id;
        RAISE EXCEPTION 'Item with BOM changed to purchased';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    ASSERT NOT EXISTS (SELECT 1 FROM items i WHERE i.id IN(raw_id,made_id,sub_id)
        AND i.quantity<>(SELECT coalesce(sum(v.quantity),0) FROM inventory v WHERE v.item_id=i.id)), 'All Item quantities equal inventory totals';
    RAISE NOTICE 'PASS: item creation, receipts, sales, edits, cancellation, warehouses, BOM, production, rollback, reservations, ledger';
END $$;
