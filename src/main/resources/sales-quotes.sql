CREATE TABLE IF NOT EXISTS sales_headers (
 id bigserial PRIMARY KEY,
 document_type varchar(20) NOT NULL DEFAULT 'QUOTE' CHECK (document_type IN ('QUOTE','ORDER')),
 quote_no varchar(50) NOT NULL UNIQUE,
 customer_id bigint NOT NULL REFERENCES customers(id),
 location_id bigint REFERENCES locations(id),
 order_date date NOT NULL DEFAULT CURRENT_DATE,
 requested_delivery_date date,
 currency_code varchar(20) NOT NULL DEFAULT 'VND',
 payment_terms_code varchar(255), payment_method_code varchar(255), salesperson_code varchar(255),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','CONVERTED')),
 subtotal numeric(18,2) NOT NULL DEFAULT 0, discount_amount numeric(18,2) NOT NULL DEFAULT 0,
 vat_amount numeric(18,2) NOT NULL DEFAULT 0, total_amount numeric(18,2) NOT NULL DEFAULT 0,
 notes text, sales_order_id bigint UNIQUE REFERENCES sales_orders(id),
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sales_quote_lines (
 id bigserial PRIMARY KEY,
 sales_header_id bigint NOT NULL REFERENCES sales_headers(id),
 line_no integer NOT NULL, item_id bigint NOT NULL REFERENCES items(id),
 description varchar(255), unit_of_measure varchar(255),
 quantity numeric(18,3) NOT NULL CHECK (quantity>0),
 unit_price numeric(18,2) NOT NULL CHECK (unit_price>=0),
 discount_percent numeric(5,2) NOT NULL CHECK (discount_percent BETWEEN 0 AND 100),
 vat_percent numeric(5,2) NOT NULL CHECK (vat_percent BETWEEN 0 AND 100),
 UNIQUE(sales_header_id,line_no)
);
