-- Run inside BEGIN / ROLLBACK after sales-fields-v4 and sales-lines-v5.
DO $$
DECLARE item bigint; wh bigint; sale bigint; header bigint; quote bigint; line bigint; canonical bigint;
 marker text='COMMON-SALES-TEST-'||substr(gen_random_uuid()::text,1,8);
BEGIN
 wh=erp_default_location();
 INSERT INTO items(item_no,description) VALUES(marker,'Common Sales test') RETURNING id INTO item;
 PERFORM erp_stock_change(item,wh,10,'common_test',1);
 INSERT INTO sales_headers(document_type,quote_no,customer_id,location_id,status,external_document_no,quote_valid_until_date)
 VALUES('QUOTE',marker||'-Q',(SELECT id FROM customers ORDER BY id LIMIT 1),wh,'DRAFT','PO-TEST','2026-12-31') RETURNING id INTO quote;
 INSERT INTO sales_quote_lines(sales_header_id,line_no,item_id,quantity,unit_price,discount_percent,vat_percent)
 VALUES(quote,10000,item,2,100000,10,8);
 ASSERT (SELECT count(*)=1 FROM sales_lines WHERE sales_header_id=quote),'Quote visible in common lines';
 ASSERT (SELECT line_amount=194400 FROM sales_lines WHERE sales_header_id=quote),'Exact discount and VAT';
 ASSERT (SELECT quantity=10 FROM items WHERE id=item),'Quote never posts stock';
 INSERT INTO sales_orders(order_no,customer_id,location_id,status) VALUES(marker,(SELECT id FROM customers ORDER BY id LIMIT 1),wh,'OPEN') RETURNING id INTO sale;
 INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,quantity,unit_price,discount_percent,vat_percent)
 VALUES(sale,10000,item,2,100000,10,8) RETURNING id INTO line;
 INSERT INTO sales_headers(document_type,quote_no,customer_id,location_id,status,sales_order_id)
 VALUES('ORDER',marker,(SELECT id FROM customers ORDER BY id LIMIT 1),wh,'OPEN',sale) RETURNING id INTO header;
 SELECT id INTO canonical FROM sales_lines WHERE sales_header_id=header;
 ASSERT canonical IS NOT NULL,'Order line backfilled on header creation';
 ASSERT (SELECT quantity=8 FROM items WHERE id=item),'Only source adapter subtracts stock';
 UPDATE sales_order_lines SET quantity=3 WHERE id=line;
 ASSERT (SELECT quantity=3 AND id=canonical FROM sales_lines WHERE sales_header_id=header),'Stable common line ID and quantity synchronization';
 ASSERT (SELECT quantity=7 FROM items WHERE id=item),'Only delta is posted';
 PERFORM erp_refresh_sales_lines(header);
 PERFORM erp_refresh_sales_lines(header);
 ASSERT (SELECT quantity=7 FROM items WHERE id=item),'Snapshot refresh never posts stock';
 UPDATE sales_headers SET external_document_no='PO-UPDATED',sell_to_address='Địa chỉ Việt Nam' WHERE id=header;
 ASSERT (SELECT quantity=7 FROM items WHERE id=item),'Header field update never posts stock';
 UPDATE sales_orders SET status='CANCELLED' WHERE id=sale;
 ASSERT (SELECT quantity=10 FROM items WHERE id=item),'Cancellation restores stock once';
 ASSERT (SELECT status='CANCELLED' FROM sales_headers WHERE id=header),'Common header status synchronized';
 RAISE NOTICE 'PASS: shared Sales Header/Lines, precision, stable IDs, no double stock, cancellation';
END $$;
