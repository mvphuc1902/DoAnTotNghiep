CREATE TABLE IF NOT EXISTS sales_setup_codes (
 id bigserial PRIMARY KEY, category varchar(40) NOT NULL, code varchar(50) NOT NULL,
 name varchar(255) NOT NULL, active boolean NOT NULL DEFAULT true, UNIQUE(category,code)
);
ALTER TABLE sales_headers DROP CONSTRAINT IF EXISTS sales_headers_sales_order_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS sales_headers_type_order_key ON sales_headers(document_type,sales_order_id) WHERE sales_order_id IS NOT NULL;
INSERT INTO sales_setup_codes(category,code,name) VALUES('COUNTRY','VN','Việt Nam') ON CONFLICT DO NOTHING;
ALTER TABLE sales_headers
 ADD COLUMN IF NOT EXISTS sell_to_name varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_address varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_address_2 varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_city varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_state varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_post_code varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_country_code varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_contact_no varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_phone varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_mobile varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_email varchar(255),
 ADD COLUMN IF NOT EXISTS sell_to_contact varchar(255),
 ADD COLUMN IF NOT EXISTS external_document_no varchar(255),
 ADD COLUMN IF NOT EXISTS your_reference varchar(255),
 ADD COLUMN IF NOT EXISTS work_description text,
 ADD COLUMN IF NOT EXISTS document_date date,
 ADD COLUMN IF NOT EXISTS vat_date date,
 ADD COLUMN IF NOT EXISTS due_date date,
 ADD COLUMN IF NOT EXISTS promised_delivery_date date,
 ADD COLUMN IF NOT EXISTS quote_valid_until_date date,
 ADD COLUMN IF NOT EXISTS campaign_no varchar(255),
 ADD COLUMN IF NOT EXISTS opportunity_no varchar(255),
 ADD COLUMN IF NOT EXISTS responsibility_center varchar(255),
 ADD COLUMN IF NOT EXISTS assigned_user_id varchar(255),
 ADD COLUMN IF NOT EXISTS customer_template_code varchar(255),
 ADD COLUMN IF NOT EXISTS sales_contract_id varchar(255),
 ADD COLUMN IF NOT EXISTS company_bank_account_code varchar(255),
 ADD COLUMN IF NOT EXISTS vat_country_code varchar(255),
 ADD COLUMN IF NOT EXISTS vat_registration_no varchar(255),
 ADD COLUMN IF NOT EXISTS gen_bus_posting_group varchar(255),
 ADD COLUMN IF NOT EXISTS vat_bus_posting_group varchar(255),
 ADD COLUMN IF NOT EXISTS customer_posting_group varchar(255),
 ADD COLUMN IF NOT EXISTS shortcut_dimension_1 varchar(255),
 ADD COLUMN IF NOT EXISTS shortcut_dimension_2 varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_name varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_address varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_address_2 varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_city varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_post_code varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_country_code varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_phone varchar(255),
 ADD COLUMN IF NOT EXISTS ship_to_contact varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_name varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_address varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_address_2 varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_city varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_post_code varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_country_code varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_phone varchar(255),
 ADD COLUMN IF NOT EXISTS bill_to_contact varchar(255),
 ADD COLUMN IF NOT EXISTS shipment_date date,
 ADD COLUMN IF NOT EXISTS shipment_method_code varchar(255),
 ADD COLUMN IF NOT EXISTS shipping_agent_code varchar(255),
 ADD COLUMN IF NOT EXISTS package_tracking_no varchar(255);

UPDATE sales_headers h SET sell_to_name=c.name,sell_to_address=c.address,sell_to_phone=c.phone,sell_to_email=c.email,
 document_date=h.order_date FROM customers c WHERE c.id=h.customer_id;

CREATE TABLE IF NOT EXISTS sales_document_archives (
 id bigserial PRIMARY KEY,sales_header_id bigint NOT NULL REFERENCES sales_headers(id),
 version integer NOT NULL,snapshot jsonb NOT NULL,created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(sales_header_id,version)
);
CREATE TABLE IF NOT EXISTS sales_quote_invoice_links (
 sales_header_id bigint PRIMARY KEY REFERENCES sales_headers(id),sales_invoice_id bigint NOT NULL UNIQUE REFERENCES sales_invoices(id)
);
