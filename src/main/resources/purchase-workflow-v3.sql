-- Posted purchasing events are separate from drafts. Never post historical drafts automatically.
ALTER TABLE purchase_headers ADD COLUMN IF NOT EXISTS source_quote_id bigint REFERENCES purchase_headers(id);
CREATE UNIQUE INDEX IF NOT EXISTS purchase_one_order_per_quote ON purchase_headers(source_quote_id) WHERE source_quote_id IS NOT NULL;
CREATE TABLE purchase_vendor_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 invoice_id bigint NOT NULL REFERENCES purchase_headers(id),
 vendor_id bigint NOT NULL REFERENCES vendors(id),
 entry_type varchar(20) NOT NULL CHECK(entry_type IN ('INVOICE','PAYMENT','CREDIT_MEMO','REFUND')),
 document_no varchar(60) NOT NULL UNIQUE,
 posting_date date NOT NULL,
 currency_code varchar(10) NOT NULL,
 amount numeric(18,2) NOT NULL,
 reference text NOT NULL,
 vat_amount numeric(18,2) NOT NULL DEFAULT 0,
 return_cost numeric(18,2) NOT NULL DEFAULT 0,
 return_order_id bigint REFERENCES purchase_headers(id),
 request_key uuid UNIQUE,
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((entry_type IN ('INVOICE','REFUND') AND amount>=0) OR (entry_type IN ('PAYMENT','CREDIT_MEMO') AND amount<0))
);
CREATE UNIQUE INDEX purchase_invoice_post_once ON purchase_vendor_entries(invoice_id) WHERE entry_type='INVOICE';
CREATE INDEX purchase_return_credit_idx ON purchase_vendor_entries(return_order_id) WHERE return_order_id IS NOT NULL;
CREATE TABLE purchase_receiving_requests (
 request_key uuid PRIMARY KEY,
 order_id bigint NOT NULL REFERENCES purchase_headers(id),
 receipt_id bigint NOT NULL REFERENCES purchase_receipts(id),
 invoice_id bigint REFERENCES purchase_headers(id),
 payload jsonb NOT NULL
);
CREATE TABLE purchase_return_shipments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 return_order_id bigint NOT NULL UNIQUE REFERENCES purchase_headers(id),
 posting_date date NOT NULL,
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE purchase_return_shipments ADD COLUMN inventory_value numeric(18,2) NOT NULL DEFAULT 0;
CREATE TABLE purchase_posting_setup (
 role varchar(30) PRIMARY KEY CHECK(role IN ('INVENTORY','EXPENSE','VAT','PAYABLE','BANK','RETURN_CLEARING','ADJUSTMENT')),
 account_id bigint NOT NULL REFERENCES accounts(id)
);
CREATE TABLE purchase_value_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 purchase_line_id bigint NOT NULL REFERENCES purchase_lines(id),
 receipt_line_id bigint REFERENCES purchase_receipt_lines(id),
 entry_type varchar(20) NOT NULL CHECK(entry_type IN ('EXPECTED','ACTUAL','RETURN')),
 quantity numeric(18,3) NOT NULL,
 amount numeric(18,2) NOT NULL,
 currency_code varchar(10) NOT NULL,
 posting_date date NOT NULL
);
CREATE TABLE purchase_gl_links (
 ledger_id bigint PRIMARY KEY REFERENCES general_ledger(id),
 purchase_header_id bigint NOT NULL REFERENCES purchase_headers(id),
 currency_code varchar(10) NOT NULL
);
-- A configured account is required; never guess a company's chart of accounts.
-- Initial mappings for this project's VND demo chart; editable in Purchase posting setup.
INSERT INTO accounts(account_no,account_name,account_type,active)
SELECT '1331','Thuế GTGT đầu vào','ASSET',true WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE account_no='1331');
INSERT INTO accounts(account_no,account_name,account_type,active)
SELECT '1388','Hàng gửi trả chờ giảm công nợ','ASSET',true WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE account_no='1388');
INSERT INTO purchase_posting_setup(role,account_id)
SELECT m.role,a.id FROM (VALUES ('INVENTORY','156'),('EXPENSE','632'),('VAT','1331'),('PAYABLE','331'),('BANK','112'),('RETURN_CLEARING','1388'),('ADJUSTMENT','632')) m(role,code)
JOIN accounts a ON a.account_no=m.code AND a.active=true ON CONFLICT(role) DO NOTHING;
-- Quantity Invoiced means posted invoices; draft invoice lines still reserve receipt availability.
CREATE OR REPLACE FUNCTION sync_purchase_invoiced_quantity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE purchase_lines l SET quantity_invoiced=(
  SELECT coalesce(sum(i.quantity),0) FROM purchase_lines i JOIN purchase_headers h ON h.id=i.purchase_header_id
  WHERE i.source_purchase_line_id=l.id AND i.document_type='INVOICE' AND h.status='POSTED')
 WHERE l.document_type='ORDER' AND l.id IN (
  CASE WHEN TG_OP<>'INSERT' THEN OLD.source_purchase_line_id END,
  CASE WHEN TG_OP<>'DELETE' THEN NEW.source_purchase_line_id END);
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
-- Preserve historical quantities until an explicit posting operation touches the order.
CREATE OR REPLACE FUNCTION purchase_posted_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='general_ledger' THEN
  IF EXISTS(SELECT 1 FROM purchase_gl_links WHERE ledger_id=OLD.id) THEN
   RAISE EXCEPTION 'Posted Purchase G/L entries cannot be edited or deleted.' USING ERRCODE='23514';
  END IF;
 ELSE
  RAISE EXCEPTION 'Posted purchasing history cannot be edited or deleted.' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER purchase_gl_immutable BEFORE UPDATE OR DELETE ON general_ledger FOR EACH ROW EXECUTE FUNCTION purchase_posted_immutable();
CREATE TRIGGER purchase_vendor_immutable BEFORE UPDATE OR DELETE ON purchase_vendor_entries FOR EACH ROW EXECUTE FUNCTION purchase_posted_immutable();
CREATE TRIGGER purchase_value_immutable BEFORE UPDATE OR DELETE ON purchase_value_entries FOR EACH ROW EXECUTE FUNCTION purchase_posted_immutable();
CREATE TRIGGER purchase_receipt_immutable BEFORE UPDATE OR DELETE ON purchase_receipts FOR EACH ROW EXECUTE FUNCTION purchase_posted_immutable();
CREATE TRIGGER purchase_receipt_line_immutable BEFORE UPDATE OR DELETE ON purchase_receipt_lines FOR EACH ROW EXECUTE FUNCTION purchase_posted_immutable();
