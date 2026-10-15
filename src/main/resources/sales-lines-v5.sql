-- Shared read model. Legacy write adapters retain stock/return/invoice guards.
CREATE TABLE IF NOT EXISTS sales_lines (
 id bigserial PRIMARY KEY,
 sales_header_id bigint NOT NULL REFERENCES sales_headers(id) ON DELETE CASCADE,
 -- Historical source lines may refer to retired items. Preserve their IDs without inventing replacements.
 line_no integer NOT NULL, item_id bigint NOT NULL,
 description varchar(255), unit_of_measure varchar(255),
 quantity numeric(18,3) NOT NULL, unit_price numeric(18,2) NOT NULL,
 discount_percent numeric(5,2) NOT NULL DEFAULT 0, discount_amount numeric(18,2) NOT NULL DEFAULT 0,
 vat_percent numeric(5,2) NOT NULL DEFAULT 0, vat_amount numeric(18,2) NOT NULL DEFAULT 0,
 line_amount numeric(18,2) NOT NULL DEFAULT 0,
 quantity_shipped numeric(18,3) NOT NULL DEFAULT 0, quantity_invoiced numeric(18,3) NOT NULL DEFAULT 0,
 location_id bigint REFERENCES locations(id), created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 source_table varchar(40) NOT NULL, source_line_id bigint NOT NULL,
 UNIQUE(sales_header_id,source_table,source_line_id)
);
CREATE INDEX IF NOT EXISTS sales_lines_header_idx ON sales_lines(sales_header_id);

CREATE OR REPLACE FUNCTION erp_refresh_sales_lines(hid bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE h sales_headers%ROWTYPE;
BEGIN
 SELECT * INTO h FROM sales_headers WHERE id=hid FOR UPDATE;
 IF NOT FOUND THEN RETURN; END IF;
 INSERT INTO sales_lines(sales_header_id,line_no,item_id,description,unit_of_measure,quantity,unit_price,
 discount_percent,discount_amount,vat_percent,vat_amount,line_amount,quantity_shipped,quantity_invoiced,location_id,source_table,source_line_id)
 SELECT hid,line_no,item_id,description,unit_of_measure,quantity,unit_price,discount_percent,
 round(quantity*unit_price*discount_percent/100,2),vat_percent,
 round(round(quantity*unit_price*(1-discount_percent/100),2)*vat_percent/100,2),
 round(quantity*unit_price*(1-discount_percent/100),2)+round(round(quantity*unit_price*(1-discount_percent/100),2)*vat_percent/100,2),
 quantity_shipped,quantity_invoiced,h.location_id,source_table,source_line_id
 FROM (
 SELECT l.line_no,l.item_id,l.description,l.unit_of_measure,l.quantity,coalesce(l.unit_price,0) AS unit_price,coalesce(l.discount_percent,0) AS discount_percent,coalesce(l.vat_percent,0) AS vat_percent,
 0::numeric AS quantity_shipped,0::numeric AS quantity_invoiced,'sales_quote_lines'::varchar AS source_table,l.id AS source_line_id
 FROM sales_quote_lines l WHERE h.document_type='QUOTE' AND l.sales_header_id=hid
 UNION ALL
 SELECT l.line_no,l.item_id,l.description,l.unit_of_measure,l.quantity,coalesce(l.unit_price,0) AS unit_price,coalesce(l.discount_percent,0) AS discount_percent,coalesce(l.vat_percent,0) AS vat_percent,
 coalesce(l.quantity_shipped,0),coalesce(l.quantity_invoiced,0),'sales_order_lines',l.id
 FROM sales_order_lines l WHERE h.document_type='ORDER' AND l.sales_order_id=h.sales_order_id
 UNION ALL
 SELECT s.line_no,l.item_id,s.description,s.unit_of_measure,l.quantity,coalesce(s.unit_price,0),coalesce(s.discount_percent,0),coalesce(s.vat_percent,0),0,0,'sales_return_lines',l.id
 FROM sales_return_lines l JOIN sales_order_lines s ON s.id=l.sales_order_line_id WHERE h.document_type='RETURN_ORDER' AND l.return_id=h.sales_return_id
 UNION ALL
 SELECT l.line_no,l.item_id,l.description,i.base_unit_of_measure,l.quantity,coalesce(l.unit_price,0),0,coalesce(l.vat_percent,0),0,l.quantity,'sales_invoice_lines',l.id
 FROM sales_invoice_lines l LEFT JOIN items i ON i.id=l.item_id WHERE h.document_type='INVOICE' AND l.invoice_id=h.sales_invoice_id
 ) lines
 ON CONFLICT(sales_header_id,source_table,source_line_id) DO UPDATE SET
 line_no=EXCLUDED.line_no,item_id=EXCLUDED.item_id,description=EXCLUDED.description,unit_of_measure=EXCLUDED.unit_of_measure,
 quantity=EXCLUDED.quantity,unit_price=EXCLUDED.unit_price,discount_percent=EXCLUDED.discount_percent,discount_amount=EXCLUDED.discount_amount,
 vat_percent=EXCLUDED.vat_percent,vat_amount=EXCLUDED.vat_amount,line_amount=EXCLUDED.line_amount,location_id=EXCLUDED.location_id,
 quantity_shipped=EXCLUDED.quantity_shipped,quantity_invoiced=EXCLUDED.quantity_invoiced;
 DELETE FROM sales_lines l WHERE l.sales_header_id=hid AND NOT CASE l.source_table
 WHEN 'sales_quote_lines' THEN EXISTS(SELECT 1 FROM sales_quote_lines s WHERE s.id=l.source_line_id AND s.sales_header_id=hid)
 WHEN 'sales_order_lines' THEN EXISTS(SELECT 1 FROM sales_order_lines s WHERE s.id=l.source_line_id AND s.sales_order_id=h.sales_order_id)
 WHEN 'sales_return_lines' THEN EXISTS(SELECT 1 FROM sales_return_lines s WHERE s.id=l.source_line_id AND s.return_id=h.sales_return_id)
 WHEN 'sales_invoice_lines' THEN EXISTS(SELECT 1 FROM sales_invoice_lines s WHERE s.id=l.source_line_id AND s.invoice_id=h.sales_invoice_id)
 ELSE false END;
 UPDATE sales_headers target SET subtotal=t.net,discount_amount=t.discount,vat_amount=t.vat,total_amount=t.total
 FROM (SELECT coalesce(sum(line_amount-vat_amount),0) AS net,coalesce(sum(discount_amount),0) AS discount,
 coalesce(sum(vat_amount),0) AS vat,coalesce(sum(line_amount),0) AS total FROM sales_lines WHERE sales_header_id=hid) t
 WHERE target.id=hid AND ((target.document_type='QUOTE' AND target.status='DRAFT')
 OR (target.document_type='ORDER' AND target.status='OPEN')
 OR (target.document_type='RETURN_ORDER' AND EXISTS(SELECT 1 FROM sales_returns r WHERE r.id=target.sales_return_id AND r.status='DRAFT')))
 AND (target.subtotal,target.discount_amount,target.vat_amount,target.total_amount)
 IS DISTINCT FROM (t.net,t.discount,t.vat,t.total);
END $$;

CREATE OR REPLACE FUNCTION erp_refresh_sales_line_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE hid bigint;
BEGIN
 -- Refresh both parents when a line changes document. Header counts are small and filtering remains indexed.
 FOR hid IN SELECT id FROM sales_headers WHERE
 (TG_TABLE_NAME='sales_headers' AND id=coalesce((to_jsonb(NEW)->>'id')::bigint,(to_jsonb(OLD)->>'id')::bigint)) OR
 (TG_TABLE_NAME='sales_quote_lines' AND id IN ((to_jsonb(NEW)->>'sales_header_id')::bigint,(to_jsonb(OLD)->>'sales_header_id')::bigint)) OR
 (TG_TABLE_NAME='sales_order_lines' AND sales_order_id IN ((to_jsonb(NEW)->>'sales_order_id')::bigint,(to_jsonb(OLD)->>'sales_order_id')::bigint)) OR
 (TG_TABLE_NAME='sales_return_lines' AND sales_return_id IN ((to_jsonb(NEW)->>'return_id')::bigint,(to_jsonb(OLD)->>'return_id')::bigint)) OR
 (TG_TABLE_NAME='sales_invoice_lines' AND sales_invoice_id IN ((to_jsonb(NEW)->>'invoice_id')::bigint,(to_jsonb(OLD)->>'invoice_id')::bigint))
 ORDER BY id LOOP PERFORM erp_refresh_sales_lines(hid); END LOOP;
 RETURN NULL;
END $$;
DO $$ DECLARE t text; hid bigint; BEGIN
 FOREACH t IN ARRAY ARRAY['sales_headers','sales_quote_lines','sales_order_lines','sales_return_lines','sales_invoice_lines'] LOOP
 EXECUTE format('CREATE TRIGGER zz_sales_lines_sync AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION erp_refresh_sales_line_trigger()',t);
 END LOOP;
 FOR hid IN SELECT id FROM sales_headers ORDER BY id LOOP PERFORM erp_refresh_sales_lines(hid); END LOOP;
END $$;

-- Stock is posted exactly once by source order lines. The old function referred to a missing legacy table.
CREATE OR REPLACE FUNCTION erp_stock_header() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(7821348);
 IF TG_TABLE_NAME='sales_orders' THEN
  IF NEW.location_id IS NOT DISTINCT FROM OLD.location_id AND NEW.status=OLD.status THEN RETURN NEW; END IF;
  IF EXISTS(SELECT 1 FROM sales_order_lines l WHERE sales_order_id=NEW.id AND NOT EXISTS
   (SELECT 1 FROM erp_stock_lines s WHERE s.source_table='sales_order_lines' AND s.source_id=l.id)) THEN
   RAISE EXCEPTION 'STOCK: Đơn cũ chưa đối soát tồn kho; không được đổi kho hoặc trạng thái.' USING ERRCODE='23514';
  END IF;
  UPDATE sales_order_lines SET quantity=quantity WHERE sales_order_id=NEW.id;
 ELSE
  IF NEW.location_code IS NOT DISTINCT FROM OLD.location_code THEN RETURN NEW; END IF;
  IF EXISTS(SELECT 1 FROM purchase_receipt_lines l WHERE receipt_id=NEW.id AND NOT EXISTS
   (SELECT 1 FROM erp_stock_lines s WHERE s.source_table='purchase_receipt_lines' AND s.source_id=l.id)) THEN
   RAISE EXCEPTION 'STOCK: Phiếu nhập cũ chưa đối soát tồn kho.' USING ERRCODE='23514';
  END IF;
  UPDATE purchase_receipt_lines SET quantity=quantity WHERE receipt_id=NEW.id;
 END IF;
 RETURN NEW;
END $$;

ALTER TABLE sales_headers DROP CONSTRAINT IF EXISTS sales_headers_status_check;
ALTER TABLE sales_headers ADD CONSTRAINT sales_headers_status_check CHECK(status IN
 ('DRAFT','CONVERTED','OPEN','RELEASED','PARTIALLY_SHIPPED','SHIPPED','INVOICED','CANCELLED','RECEIVED'));
CREATE OR REPLACE FUNCTION erp_sync_order_header() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE sales_headers SET quote_no=NEW.order_no,customer_id=NEW.customer_id,location_id=NEW.location_id,
 order_date=coalesce(NEW.order_date,CURRENT_DATE),requested_delivery_date=NEW.requested_delivery_date,
 currency_code=coalesce(NEW.currency_code,'VND'),payment_terms_code=NEW.payment_terms_code,
 payment_method_code=NEW.payment_method_code,salesperson_code=NEW.salesperson_code,status=NEW.status,
 subtotal=coalesce(NEW.subtotal,0),discount_amount=coalesce(NEW.discount_amount,0),vat_amount=coalesce(NEW.vat_amount,0),
 total_amount=coalesce(NEW.total_amount,0),notes=NEW.notes,updated_at=CURRENT_TIMESTAMP
 WHERE sales_order_id=NEW.id AND document_type='ORDER';
 RETURN NULL;
END $$;
CREATE TRIGGER zz_sales_order_header_sync AFTER UPDATE ON sales_orders FOR EACH ROW EXECUTE FUNCTION erp_sync_order_header();
UPDATE sales_headers h SET status=o.status FROM sales_orders o WHERE h.sales_order_id=o.id AND h.document_type='ORDER';
INSERT INTO sales_headers(document_type,quote_no,customer_id,location_id,order_date,requested_delivery_date,currency_code,payment_terms_code,payment_method_code,salesperson_code,status,subtotal,discount_amount,vat_amount,total_amount,notes,sales_order_id)
SELECT 'ORDER',CASE WHEN EXISTS(SELECT 1 FROM sales_headers h WHERE h.quote_no=o.order_no) THEN 'SO-MIG-'||o.id ELSE o.order_no END,
o.customer_id,o.location_id,coalesce(o.order_date,CURRENT_DATE),o.requested_delivery_date,coalesce(o.currency_code,'VND'),o.payment_terms_code,o.payment_method_code,o.salesperson_code,o.status,
coalesce(o.subtotal,0),coalesce(o.discount_amount,0),coalesce(o.vat_amount,0),coalesce(o.total_amount,0),o.notes,o.id
FROM sales_orders o WHERE NOT EXISTS(SELECT 1 FROM sales_headers h WHERE h.sales_order_id=o.id AND h.document_type='ORDER');
UPDATE sales_headers target SET sell_to_name=source.sell_to_name,sell_to_address=source.sell_to_address,sell_to_address_2=source.sell_to_address_2,sell_to_city=source.sell_to_city,sell_to_state=source.sell_to_state,sell_to_post_code=source.sell_to_post_code,sell_to_country_code=source.sell_to_country_code,sell_to_contact_no=source.sell_to_contact_no,sell_to_phone=source.sell_to_phone,sell_to_mobile=source.sell_to_mobile,sell_to_email=source.sell_to_email,sell_to_contact=source.sell_to_contact,external_document_no=source.external_document_no,your_reference=source.your_reference,work_description=source.work_description,document_date=source.document_date,vat_date=source.vat_date,due_date=source.due_date,promised_delivery_date=source.promised_delivery_date,quote_valid_until_date=source.quote_valid_until_date,campaign_no=source.campaign_no,opportunity_no=source.opportunity_no,responsibility_center=source.responsibility_center,assigned_user_id=source.assigned_user_id,customer_template_code=source.customer_template_code,sales_contract_id=source.sales_contract_id,company_bank_account_code=source.company_bank_account_code,vat_country_code=source.vat_country_code,vat_registration_no=source.vat_registration_no,gen_bus_posting_group=source.gen_bus_posting_group,vat_bus_posting_group=source.vat_bus_posting_group,customer_posting_group=source.customer_posting_group,shortcut_dimension_1=source.shortcut_dimension_1,shortcut_dimension_2=source.shortcut_dimension_2,ship_to_name=source.ship_to_name,ship_to_address=source.ship_to_address,ship_to_address_2=source.ship_to_address_2,ship_to_city=source.ship_to_city,ship_to_post_code=source.ship_to_post_code,ship_to_country_code=source.ship_to_country_code,ship_to_phone=source.ship_to_phone,ship_to_contact=source.ship_to_contact,bill_to_name=source.bill_to_name,bill_to_address=source.bill_to_address,bill_to_address_2=source.bill_to_address_2,bill_to_city=source.bill_to_city,bill_to_post_code=source.bill_to_post_code,bill_to_country_code=source.bill_to_country_code,bill_to_phone=source.bill_to_phone,bill_to_contact=source.bill_to_contact,shipment_date=source.shipment_date,shipment_method_code=source.shipment_method_code,shipping_agent_code=source.shipping_agent_code,package_tracking_no=source.package_tracking_no
FROM sales_headers source WHERE source.document_type='QUOTE' AND target.document_type='ORDER' AND source.sales_order_id=target.sales_order_id;

-- Restore Sales stock hooks if an earlier schema rebuild removed them. Never repost historical rows.
DROP TRIGGER IF EXISTS erp_post_stock_line ON sales_order_lines;
CREATE TRIGGER erp_post_stock_line AFTER INSERT OR UPDATE OR DELETE ON sales_order_lines
 FOR EACH ROW EXECUTE FUNCTION erp_post_stock_line();
DROP TRIGGER IF EXISTS erp_stock_header ON sales_orders;
CREATE TRIGGER erp_stock_header AFTER UPDATE ON sales_orders FOR EACH ROW EXECUTE FUNCTION erp_stock_header();
