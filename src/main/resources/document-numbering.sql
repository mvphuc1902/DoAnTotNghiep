CREATE TABLE IF NOT EXISTS erp_document_counters(prefix text PRIMARY KEY, last_number bigint NOT NULL);
CREATE OR REPLACE FUNCTION erp_next_document_no(p_prefix text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE table_name text; field_name text; highest bigint; number bigint;
BEGIN
 CASE p_prefix
 WHEN 'SO' THEN table_name:='sales_orders'; field_name:='order_no';
 WHEN 'SI' THEN table_name:='sales_invoices'; field_name:='invoice_no';
 WHEN 'SR' THEN table_name:='sales_returns'; field_name:='return_no';
 WHEN 'SS' THEN table_name:='sales_shipments'; field_name:='shipment_no';
 WHEN 'PO','PQ','PI' THEN table_name:='purchase_headers'; field_name:='document_no';
 WHEN 'PR' THEN table_name:='purchase_returns'; field_name:='return_no';
 WHEN 'GR' THEN table_name:='purchase_receipts'; field_name:='receipt_no';
 WHEN 'ITEM' THEN table_name:='items'; field_name:='item_no';
 ELSE RAISE EXCEPTION 'Unsupported document prefix';
 END CASE;
 PERFORM pg_advisory_xact_lock(7821350);
 EXECUTE format('SELECT coalesce(max(substring(%I from length($1)+2)::bigint),0) FROM %I WHERE %I ~ ($1 || ''-[0-9]{1,15}$'') AND %I LIKE ($1 || ''-%%'')',field_name,table_name,field_name,field_name)
 INTO highest USING p_prefix;
 INSERT INTO erp_document_counters(prefix,last_number) VALUES(p_prefix,highest+1)
 ON CONFLICT(prefix) DO UPDATE SET last_number=greatest(erp_document_counters.last_number,highest)+1
 RETURNING last_number INTO number;
 RETURN p_prefix || '-' || lpad(number::text,greatest(3,length(number::text)),'0');
END $$;
