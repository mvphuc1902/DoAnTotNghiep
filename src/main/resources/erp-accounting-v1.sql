-- New posting events only. Existing documents/ledger balances are not backfilled.
INSERT INTO accounts(account_no,account_name,account_type,active) VALUES ('155','Thành phẩm','ASSET',true) ON CONFLICT(account_no) DO NOTHING;
CREATE TABLE sales_posting_setup(role varchar(30) PRIMARY KEY,account_id bigint NOT NULL REFERENCES accounts(id));
INSERT INTO sales_posting_setup SELECT x.role,a.id FROM (VALUES ('RECEIVABLE','131'),('REVENUE','511'),('VAT','3331'),('COGS','632'),('INVENTORY','156'),('FINISHED','155'),('BANK','112')) x(role,code) JOIN accounts a ON a.account_no=x.code;
CREATE TABLE sales_postings(invoice_id bigint PRIMARY KEY REFERENCES sales_invoices(id),customer_entry_id bigint NOT NULL UNIQUE REFERENCES customer_ledger(id),inventory_cost numeric(18,2) NOT NULL,posted_at timestamp NOT NULL DEFAULT now());
CREATE TABLE sales_gl_links(invoice_id bigint NOT NULL REFERENCES sales_invoices(id),ledger_id bigint NOT NULL UNIQUE REFERENCES general_ledger(id),PRIMARY KEY(invoice_id,ledger_id));
CREATE TABLE sales_settlements(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,invoice_id bigint NOT NULL REFERENCES sales_invoices(id),request_key uuid NOT NULL UNIQUE,posting_date date NOT NULL,amount numeric(18,2) NOT NULL CHECK(amount>0),reference varchar(255) NOT NULL,customer_entry_id bigint NOT NULL UNIQUE REFERENCES customer_ledger(id));
CREATE FUNCTION erp_sales_account(role_name text) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE result bigint;
BEGIN SELECT s.account_id INTO result FROM sales_posting_setup s JOIN accounts a ON a.id=s.account_id WHERE s.role=role_name AND a.active;
 IF result IS NULL THEN RAISE EXCEPTION 'ACCOUNTING: Thiếu tài khoản đang hoạt động: %',role_name USING ERRCODE='23514'; END IF; RETURN result;
END $$;
CREATE FUNCTION erp_sales_gl(invoice bigint,no text,kind text,day date,role_name text,value numeric) RETURNS void LANGUAGE plpgsql AS $$
DECLARE ledger bigint;
BEGIN IF value=0 THEN RETURN; END IF;
 INSERT INTO general_ledger(document_no,document_type,posting_date,account_id,description,debit,credit) VALUES(no,kind,day,erp_sales_account(role_name),'Sales '||no,greatest(value,0),greatest(-value,0)) RETURNING id INTO ledger;
 INSERT INTO sales_gl_links VALUES(invoice,ledger);
END $$;
CREATE FUNCTION erp_post_sales_invoice() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h sales_invoices%ROWTYPE; ln record; cl bigint; cost numeric:=0; raw_cost numeric:=0; finished_cost numeric:=0; lc numeric;
BEGIN
 PERFORM pg_advisory_xact_lock(7821348);
 SELECT * INTO h FROM sales_invoices WHERE id=NEW.id FOR UPDATE;
 IF NOT FOUND OR EXISTS(SELECT 1 FROM sales_postings WHERE invoice_id=h.id) THEN RETURN NULL; END IF;
 IF h.currency_code<>'VND' THEN RAISE EXCEPTION 'ACCOUNTING: Ghi sổ Sales hiện hỗ trợ VND.' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM sales_invoice_lines WHERE invoice_id=h.id) THEN RAISE EXCEPTION 'ACCOUNTING: Hóa đơn cần có dòng hàng trước khi lưu.' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM customers WHERE id=h.customer_id AND blocked) THEN RAISE EXCEPTION 'ACCOUNTING: Khách hàng đang bị khóa.' USING ERRCODE='23514'; END IF;
 FOR ln IN SELECT l.*,i.item_category,i.item_type,o.location_id FROM sales_invoice_lines l JOIN items i ON i.id=l.item_id JOIN sales_orders o ON o.id=h.sales_order_id WHERE l.invoice_id=h.id LOOP
  lc:=0;
  IF ln.item_category='INVENTORY' THEN
   SELECT round(ln.quantity*coalesce(sum(sl.quantity*coalesce(v.unit_cost,0))/nullif(sum(sl.quantity),0),(SELECT average_cost FROM inventory WHERE item_id=ln.item_id AND location_id=ln.location_id),0),2) INTO lc
   FROM sales_shipment_lines sl LEFT JOIN LATERAL (SELECT sum(quantity_out*unit_cost)/nullif(sum(quantity_out),0) AS unit_cost FROM inventory_ledger il WHERE il.document_type='sales_shipments' AND il.document_id=sl.shipment_id AND il.item_id=sl.item_id) v ON true WHERE sl.sales_order_line_id=ln.sales_order_line_id;
  END IF;
  IF ln.item_type='MANUFACTURED' THEN finished_cost:=finished_cost+lc; ELSE raw_cost:=raw_cost+lc; END IF;
 END LOOP;
 cost:=raw_cost+finished_cost;
 INSERT INTO customer_ledger(entry_no,customer_id,document_type,document_id,posting_date,debit,credit,balance,description) VALUES(h.invoice_no,h.customer_id,'SALES_INVOICE',h.id,h.posting_date,h.total_amount,0,h.total_amount,'Hóa đơn '||h.invoice_no) RETURNING id INTO cl;
 INSERT INTO sales_postings(invoice_id,customer_entry_id,inventory_cost) VALUES(h.id,cl,cost);
 PERFORM erp_sales_gl(h.id,h.invoice_no,'SALES_INVOICE',h.posting_date,'RECEIVABLE',h.total_amount);
 PERFORM erp_sales_gl(h.id,h.invoice_no,'SALES_INVOICE',h.posting_date,'REVENUE',-(h.total_amount-h.vat_amount));
 PERFORM erp_sales_gl(h.id,h.invoice_no,'SALES_INVOICE',h.posting_date,'VAT',-h.vat_amount);
 PERFORM erp_sales_gl(h.id,h.invoice_no,'SALES_INVOICE',h.posting_date,'COGS',cost);
 PERFORM erp_sales_gl(h.id,h.invoice_no,'SALES_INVOICE',h.posting_date,'INVENTORY',-raw_cost);
 PERFORM erp_sales_gl(h.id,h.invoice_no,'SALES_INVOICE',h.posting_date,'FINISHED',-finished_cost);
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER erp_auto_sales_post AFTER INSERT ON sales_invoices DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION erp_post_sales_invoice();
CREATE FUNCTION erp_sales_posted_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE invoice bigint;
BEGIN
 IF TG_TABLE_NAME='sales_invoices' THEN invoice:=OLD.id;
 ELSIF TG_TABLE_NAME='sales_invoice_lines' THEN invoice:=CASE WHEN TG_OP='INSERT' THEN NEW.invoice_id ELSE OLD.invoice_id END;
  IF TG_OP='UPDATE' AND EXISTS(SELECT 1 FROM sales_postings WHERE invoice_id=NEW.invoice_id) THEN RAISE EXCEPTION 'ACCOUNTING: Không sửa hóa đơn đã ghi sổ.' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='customer_ledger' THEN
  IF EXISTS(SELECT 1 FROM sales_postings WHERE customer_entry_id=OLD.id) OR EXISTS(SELECT 1 FROM sales_settlements WHERE customer_entry_id=OLD.id) THEN RAISE EXCEPTION 'ACCOUNTING: Công nợ đã ghi sổ chỉ đọc.' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='general_ledger' THEN
  IF EXISTS(SELECT 1 FROM sales_gl_links WHERE ledger_id=OLD.id) THEN RAISE EXCEPTION 'ACCOUNTING: G/L Sales đã ghi sổ chỉ đọc.' USING ERRCODE='23514'; END IF;
 END IF;
 IF invoice IS NOT NULL AND EXISTS(SELECT 1 FROM sales_postings WHERE invoice_id=invoice) THEN RAISE EXCEPTION 'ACCOUNTING: Hóa đơn đã ghi sổ; không sửa/xóa. Cần nghiệp vụ điều chỉnh.' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER erp_sales_posted_guard BEFORE UPDATE OR DELETE ON sales_invoices FOR EACH ROW EXECUTE FUNCTION erp_sales_posted_guard();
CREATE TRIGGER erp_sales_posted_guard BEFORE INSERT OR UPDATE OR DELETE ON sales_invoice_lines FOR EACH ROW EXECUTE FUNCTION erp_sales_posted_guard();
CREATE TRIGGER erp_sales_posted_guard BEFORE UPDATE OR DELETE ON customer_ledger FOR EACH ROW EXECUTE FUNCTION erp_sales_posted_guard();
CREATE TRIGGER erp_sales_posted_guard BEFORE UPDATE OR DELETE ON general_ledger FOR EACH ROW EXECUTE FUNCTION erp_sales_posted_guard();

CREATE TABLE production_cost_entries(production_order_id bigint NOT NULL REFERENCES production_orders(id),component_item_id bigint NOT NULL REFERENCES items(id),quantity numeric(18,3) NOT NULL,unit_cost numeric(18,2) NOT NULL,amount numeric(18,2) NOT NULL,PRIMARY KEY(production_order_id,component_item_id));
CREATE TABLE production_gl_links(production_order_id bigint NOT NULL REFERENCES production_orders(id),ledger_id bigint NOT NULL UNIQUE REFERENCES general_ledger(id));
CREATE FUNCTION erp_production_ledger_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF EXISTS(SELECT 1 FROM production_gl_links WHERE ledger_id=OLD.id) THEN RAISE EXCEPTION 'ACCOUNTING: G/L sản xuất đã ghi sổ chỉ đọc.' USING ERRCODE='23514'; END IF; IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END $$;
CREATE TRIGGER erp_production_ledger_guard BEFORE UPDATE OR DELETE ON general_ledger FOR EACH ROW EXECUTE FUNCTION erp_production_ledger_guard();

CREATE OR REPLACE FUNCTION erp_complete_production() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE component record; required numeric; component_cost numeric; total_cost numeric:=0; raw_cost numeric:=0; finished_cost numeric:=0; old_qty numeric; old_cost numeric; output_cost numeric; ledger bigint; posting record;
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
                   AND item_type='MANUFACTURED' AND item_category='INVENTORY' AND NOT coalesce(blocked,false)) THEN
        RAISE EXCEPTION 'STOCK: Lệnh sản xuất phải chọn Item loại Sản xuất còn hoạt động.' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.status='COMPLETED' THEN
        IF NOT EXISTS(SELECT 1 FROM item_bom WHERE parent_item_id=NEW.bom_parent_item_id) THEN
            RAISE EXCEPTION 'STOCK: Chưa có định mức BOM cho Item này.' USING ERRCODE='23514';
        END IF;
        FOR component IN SELECT bom.*,item.blocked,item.item_type FROM item_bom bom
            JOIN items item ON item.id=bom.component_item_id
            WHERE bom.parent_item_id=NEW.bom_parent_item_id ORDER BY bom.component_item_id LOOP
            IF coalesce(component.blocked,false) THEN
                RAISE EXCEPTION 'STOCK: Linh kiện đang bị khóa.' USING ERRCODE='23514';
            END IF;
            required=component.quantity*NEW.quantity;
            IF required<>round(required,3) THEN
                RAISE EXCEPTION 'STOCK: Số lượng linh kiện tính theo BOM vượt độ chính xác 3 chữ số thập phân.' USING ERRCODE='23514';
            END IF;
            SELECT coalesce((SELECT average_cost FROM inventory WHERE item_id=component.component_item_id AND location_id=NEW.location_id),0) INTO component_cost;
            INSERT INTO production_cost_entries VALUES(NEW.id,component.component_item_id,required,component_cost,round(required*component_cost,2));
            total_cost:=total_cost+round(required*component_cost,2);
            IF component.item_type='MANUFACTURED' THEN finished_cost:=finished_cost+round(required*component_cost,2); ELSE raw_cost:=raw_cost+round(required*component_cost,2); END IF;
            PERFORM erp_stock_change(component.component_item_id,NEW.location_id,-required,'PRODUCTION',NEW.id);
            INSERT INTO production_components(production_order_id,component_item_id,quantity_per_unit,consumed_quantity)
            VALUES(NEW.id,component.component_item_id,component.quantity,required);
        END LOOP;
        output_cost:=round(total_cost/NEW.quantity,2);
        INSERT INTO inventory(item_id,location_id,quantity,average_cost) VALUES(NEW.bom_parent_item_id,NEW.location_id,0,output_cost) ON CONFLICT(item_id,location_id) DO NOTHING;
        SELECT quantity,average_cost INTO old_qty,old_cost FROM inventory WHERE item_id=NEW.bom_parent_item_id AND location_id=NEW.location_id FOR UPDATE;
        UPDATE inventory SET average_cost=output_cost WHERE item_id=NEW.bom_parent_item_id AND location_id=NEW.location_id;
        PERFORM erp_stock_change(NEW.bom_parent_item_id,NEW.location_id,NEW.quantity,'PRODUCTION',NEW.id);
        UPDATE inventory SET average_cost=round((old_qty*old_cost+total_cost)/(old_qty+NEW.quantity),2) WHERE item_id=NEW.bom_parent_item_id AND location_id=NEW.location_id;
        FOR posting IN SELECT * FROM (VALUES ('FINISHED',total_cost),('INVENTORY',-raw_cost),('FINISHED',-finished_cost)) v(role,value) WHERE value<>0 LOOP
          INSERT INTO general_ledger(document_no,document_type,posting_date,account_id,description,debit,credit) VALUES(NEW.production_no,'PRODUCTION_COST',CURRENT_DATE,erp_sales_account(posting.role),'Giá thành nguyên liệu '||NEW.production_no,greatest(posting.value,0),greatest(-posting.value,0)) RETURNING id INTO ledger;
          INSERT INTO production_gl_links VALUES(NEW.id,ledger);
        END LOOP;
        NEW.completed_at=CURRENT_TIMESTAMP;
    END IF;
    NEW.updated_at=CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;
ALTER TABLE sales_settlements ADD COLUMN payment_id bigint NOT NULL UNIQUE REFERENCES payments(id);
CREATE FUNCTION erp_sales_payment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE payment bigint;
BEGIN
 IF TG_TABLE_NAME='payments' THEN payment:=OLD.id;
 ELSE
  IF TG_OP<>'DELETE' AND EXISTS(SELECT 1 FROM sales_postings WHERE invoice_id=NEW.sales_invoice_id) AND NOT EXISTS(SELECT 1 FROM sales_settlements WHERE payment_id=NEW.payment_id AND invoice_id=NEW.sales_invoice_id AND amount=NEW.allocated_amount) THEN RAISE EXCEPTION 'ACCOUNTING: Thu tiền hóa đơn đã ghi sổ qua nút Ghi nhận thanh toán trên card Invoice.' USING ERRCODE='23514'; END IF;
  IF TG_OP='INSERT' THEN RETURN NEW; END IF; payment:=OLD.payment_id;
 END IF;
 IF EXISTS(SELECT 1 FROM sales_settlements WHERE payment_id=payment) THEN RAISE EXCEPTION 'ACCOUNTING: Phiếu thu đã ghi sổ chỉ đọc.' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER erp_sales_payment_guard BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION erp_sales_payment_guard();
CREATE TRIGGER erp_sales_payment_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_allocations FOR EACH ROW EXECUTE FUNCTION erp_sales_payment_guard();
CREATE SEQUENCE sales_payment_no_seq;
SELECT setval('sales_payment_no_seq',greatest(1,coalesce((SELECT max(substring(payment_no from 4)::bigint)+1 FROM payments WHERE payment_no ~ '^PT-[0-9]+$'),1)),false);
-- Units remain chosen on the existing forms; reject ambiguous unit changes rather than corrupt stock.
CREATE FUNCTION erp_document_unit_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE base text;
BEGIN
 SELECT base_unit_of_measure INTO base FROM items WHERE id=NEW.item_id;
 IF TG_OP='UPDATE' AND NEW.item_id IS NOT DISTINCT FROM OLD.item_id AND NEW.unit_of_measure IS NOT DISTINCT FROM OLD.unit_of_measure THEN RETURN NEW; END IF;
 IF nullif(NEW.unit_of_measure,'') IS NULL THEN NEW.unit_of_measure:=base; END IF;
 IF nullif(base,'') IS NOT NULL AND NEW.unit_of_measure IS DISTINCT FROM base THEN RAISE EXCEPTION 'UNIT: Item dùng đơn vị cơ sở %. Chưa cấu hình hệ số quy đổi từ %; hãy chọn đúng đơn vị cơ sở để không sai tồn kho.',base,NEW.unit_of_measure USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER erp_document_unit_guard BEFORE INSERT OR UPDATE ON sales_order_lines FOR EACH ROW EXECUTE FUNCTION erp_document_unit_guard();
CREATE TRIGGER erp_document_unit_guard BEFORE INSERT OR UPDATE ON sales_quote_lines FOR EACH ROW EXECUTE FUNCTION erp_document_unit_guard();
CREATE TRIGGER erp_document_unit_guard BEFORE INSERT OR UPDATE ON purchase_lines FOR EACH ROW EXECUTE FUNCTION erp_document_unit_guard();
