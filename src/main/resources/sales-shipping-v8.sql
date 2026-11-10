-- Existing orders keep their previous stock history. New orders post stock only on Shipment.
ALTER TABLE sales_orders ADD COLUMN ship_on_post boolean NOT NULL DEFAULT false;
ALTER TABLE sales_orders ALTER COLUMN ship_on_post SET DEFAULT true;
ALTER TABLE sales_orders ADD COLUMN released_at timestamp;
CREATE TABLE sales_shipments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shipment_no varchar(50) NOT NULL UNIQUE,
 sales_order_id bigint NOT NULL REFERENCES sales_orders(id),
 customer_id bigint NOT NULL REFERENCES customers(id),
 shipment_date date NOT NULL,
 location_code varchar(30) NOT NULL,
 request_key uuid NOT NULL UNIQUE,
 request_signature text NOT NULL,
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE sales_shipment_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shipment_id bigint NOT NULL REFERENCES sales_shipments(id),
 line_no integer NOT NULL,
 sales_order_line_id bigint NOT NULL REFERENCES sales_order_lines(id),
 item_id bigint NOT NULL REFERENCES items(id),
 quantity numeric(18,3) NOT NULL CHECK(quantity>0),
 unit_of_measure varchar(30),location_code varchar(30) NOT NULL,
 UNIQUE(shipment_id,sales_order_line_id)
);
DO $$ DECLARE definition text; BEGIN
 SELECT pg_get_functiondef('erp_post_stock_line()'::regprocedure) INTO definition;
 definition=regexp_replace(definition,'BEGIN', $body$BEGIN
 IF TG_TABLE_NAME='sales_order_lines' AND EXISTS(SELECT 1 FROM sales_orders WHERE id=coalesce((to_jsonb(NEW)->>'sales_order_id')::bigint,(to_jsonb(OLD)->>'sales_order_id')::bigint) AND ship_on_post) THEN
   IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
 END IF;
 $body$);
 EXECUTE definition;
END $$;
DO $$ DECLARE definition text; BEGIN
 SELECT pg_get_functiondef('erp_stock_header()'::regprocedure) INTO definition;
 definition=regexp_replace(definition,'BEGIN', $body$BEGIN
 IF TG_TABLE_NAME='sales_orders' AND NEW.ship_on_post THEN RETURN NEW; END IF;
 $body$); EXECUTE definition;
 SELECT pg_get_functiondef('erp_return_sale_guard()'::regprocedure) INTO definition;
 definition=regexp_replace(definition,'BEGIN', $body$BEGIN
 IF TG_TABLE_NAME='sales_order_lines' AND TG_OP='UPDATE' AND current_setting('erp.sales_shipping',true)='on'
 AND (to_jsonb(NEW)-ARRAY['quantity_shipped','quantity_invoiced']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['quantity_shipped','quantity_invoiced']) THEN RETURN NEW; END IF;
 $body$); EXECUTE definition;
END $$;
CREATE FUNCTION erp_shipped_order_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE orderid bigint; released timestamp; deferred boolean;
BEGIN
 IF TG_TABLE_NAME='sales_orders' THEN
   IF OLD.ship_on_post AND (OLD.released_at IS NOT NULL) THEN
     IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['released_at','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['released_at','updated_at']) THEN
       RAISE EXCEPTION 'STOCK: Đơn đã Release không được đổi nội dung hoặc xóa.' USING ERRCODE='23514';
     END IF;
   END IF;
 ELSE
   orderid=coalesce((to_jsonb(NEW)->>'sales_order_id')::bigint,(to_jsonb(OLD)->>'sales_order_id')::bigint);
   SELECT ship_on_post,released_at INTO deferred,released FROM sales_orders WHERE id=orderid FOR UPDATE;
   IF deferred AND released IS NOT NULL THEN
     IF TG_OP='UPDATE' AND current_setting('erp.sales_shipping',true)='on'
       AND (to_jsonb(NEW)-ARRAY['quantity_shipped','quantity_invoiced']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['quantity_shipped','quantity_invoiced']) THEN RETURN NEW; END IF;
     RAISE EXCEPTION 'STOCK: Dòng đơn đã Release không được sửa/xóa trực tiếp.' USING ERRCODE='23514';
   END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER erp_shipped_order_guard BEFORE UPDATE OR DELETE ON sales_orders FOR EACH ROW EXECUTE FUNCTION erp_shipped_order_guard();
CREATE TRIGGER erp_shipped_order_guard BEFORE INSERT OR UPDATE OR DELETE ON sales_order_lines FOR EACH ROW EXECUTE FUNCTION erp_shipped_order_guard();
CREATE FUNCTION erp_invoice_shipped_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ordered bigint; shipped numeric; invoiced numeric;
BEGIN
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 SELECT sales_order_id INTO ordered FROM sales_invoices WHERE id=NEW.invoice_id;
 IF NOT EXISTS(SELECT 1 FROM sales_orders WHERE id=ordered AND ship_on_post) THEN RETURN NEW; END IF;
 IF NEW.sales_order_line_id IS NULL THEN RAISE EXCEPTION 'INVOICE: Chọn dòng đã giao của đơn bán.' USING ERRCODE='23514'; END IF;
 SELECT quantity_shipped INTO shipped FROM sales_order_lines WHERE id=NEW.sales_order_line_id AND sales_order_id=ordered FOR UPDATE;
 SELECT coalesce(sum(quantity),0) INTO invoiced FROM sales_invoice_lines WHERE sales_order_line_id=NEW.sales_order_line_id AND id<>coalesce(NEW.id,0);
 IF shipped IS NULL OR invoiced+NEW.quantity>shipped THEN RAISE EXCEPTION 'INVOICE: Số lượng hóa đơn vượt số lượng đã giao còn chưa lập hóa đơn.' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER erp_invoice_shipped_guard BEFORE INSERT OR UPDATE ON sales_invoice_lines FOR EACH ROW EXECUTE FUNCTION erp_invoice_shipped_guard();
CREATE FUNCTION erp_shipment_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'STOCK: Phiếu giao hàng đã đăng không được sửa hoặc xóa.' USING ERRCODE='23514'; END $$;
CREATE TRIGGER erp_shipment_immutable BEFORE UPDATE OR DELETE ON sales_shipments FOR EACH ROW EXECUTE FUNCTION erp_shipment_immutable();
CREATE TRIGGER erp_shipment_immutable BEFORE UPDATE OR DELETE ON sales_shipment_lines FOR EACH ROW EXECUTE FUNCTION erp_shipment_immutable();
