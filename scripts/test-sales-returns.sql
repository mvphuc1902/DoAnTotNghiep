-- Execute inside a transaction and ROLLBACK afterwards.
DO $$
DECLARE item bigint; wh bigint; sale bigint; sale2 bigint; line bigint; ret bigint; ret2 bigint; qty numeric;
 marker text='RETURN-TEST-'||substr(gen_random_uuid()::text,1,8);
BEGIN
 wh=erp_default_location();
 INSERT INTO items(item_no,description) VALUES(marker,'Return test item') RETURNING id INTO item;
 PERFORM erp_stock_change(item,wh,10,'return_test',1);
 INSERT INTO sales_orders(order_no,customer_id,location_id) VALUES(marker,(SELECT id FROM customers ORDER BY id LIMIT 1),wh) RETURNING id INTO sale;
 INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,quantity) VALUES(sale,10000,item,4) RETURNING id INTO line;
 ASSERT (SELECT quantity=6 FROM items WHERE id=item), 'Sale subtracts four';
 INSERT INTO sales_returns(return_no,sales_order_id,location_id) VALUES(marker,sale,wh) RETURNING id INTO ret;
 BEGIN
  UPDATE sales_returns SET status='RECEIVED' WHERE id=ret;
  RAISE EXCEPTION 'Empty return accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 INSERT INTO sales_return_lines(return_id,sales_order_line_id,quantity) VALUES(ret,line,1.25);
 ASSERT (SELECT item_id=item FROM sales_return_lines WHERE return_id=ret), 'Item derived from sale';
 ASSERT (SELECT quantity=6 FROM items WHERE id=item), 'Draft does not add stock';
 UPDATE sales_return_lines SET quantity=2 WHERE return_id=ret;
 UPDATE sales_returns SET status='RECEIVED' WHERE id=ret;
 ASSERT (SELECT quantity=8 FROM items WHERE id=item), 'Receive restores quantity';
 UPDATE sales_returns SET status='RECEIVED' WHERE id=ret;
 ASSERT (SELECT quantity=8 FROM items WHERE id=item), 'Repeat does not double stock';
 ASSERT (SELECT count(*)=1 FROM inventory_ledger WHERE document_type='sales_returns' AND document_id=ret), 'One stock posting';
 BEGIN
  UPDATE sales_return_lines SET quantity=1 WHERE return_id=ret;
  RAISE EXCEPTION 'Received line edit accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN
  DELETE FROM sales_returns WHERE id=ret;
  RAISE EXCEPTION 'Received return delete accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN
  UPDATE sales_orders SET status='CANCELLED' WHERE id=sale;
  RAISE EXCEPTION 'Original cancellation accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN
  UPDATE sales_order_lines SET quantity=3 WHERE id=line;
  RAISE EXCEPTION 'Original quantity edit accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 INSERT INTO sales_returns(return_no,sales_order_id,location_id) VALUES(marker||'-2',sale,wh) RETURNING id INTO ret2;
 INSERT INTO sales_return_lines(return_id,sales_order_line_id,quantity) VALUES(ret2,line,3);
 BEGIN
  UPDATE sales_returns SET status='RECEIVED' WHERE id=ret2;
  RAISE EXCEPTION 'Aggregate over-return accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 ASSERT (SELECT status='DRAFT' FROM sales_returns WHERE id=ret2), 'Failed receive rolls back';
 ASSERT (SELECT quantity=8 FROM items WHERE id=item), 'Rejected return leaves stock unchanged';
 UPDATE sales_return_lines SET quantity=2 WHERE return_id=ret2;
 UPDATE sales_returns SET status='RECEIVED' WHERE id=ret2;
 ASSERT (SELECT quantity=10 FROM items WHERE id=item), 'Remaining return restores original stock';
 INSERT INTO sales_orders(order_no,customer_id,location_id) VALUES(marker||'-2',(SELECT id FROM customers ORDER BY id LIMIT 1),wh) RETURNING id INTO sale2;
 INSERT INTO sales_returns(return_no,sales_order_id,location_id) VALUES(marker||'-3',sale2,wh) RETURNING id INTO ret2;
 BEGIN
  INSERT INTO sales_return_lines(return_id,sales_order_line_id,quantity) VALUES(ret2,line,1);
  RAISE EXCEPTION 'Unrelated line accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 UPDATE sales_returns SET status='CANCELLED' WHERE id=ret2;
 ASSERT (SELECT quantity=10 FROM items WHERE id=item), 'Cancel draft leaves stock unchanged';
 RAISE NOTICE 'PASS: return drafts, receive, duplicate receive, over-return, immutable posted documents, original sale guards, wrong order and cancellation';
END $$;
