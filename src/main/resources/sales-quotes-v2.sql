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

INSERT INTO sales_headers(
 document_type,quote_no,customer_id,location_id,order_date,requested_delivery_date,
 currency_code,payment_terms_code,payment_method_code,salesperson_code,status,
 subtotal,discount_amount,vat_amount,total_amount,notes,sales_order_id
)
SELECT 'ORDER',
       CASE WHEN EXISTS (SELECT 1 FROM sales_headers existing WHERE existing.quote_no=o.order_no)
            THEN 'SO-MIG-' || o.id ELSE o.order_no END,
       o.customer_id,o.location_id,coalesce(o.order_date,CURRENT_DATE),o.requested_delivery_date,
       coalesce(o.currency_code,'VND'),o.payment_terms_code,o.payment_method_code,o.salesperson_code,
       'CONVERTED',coalesce(o.subtotal,0),coalesce(o.discount_amount,0),coalesce(o.vat_amount,0),
       coalesce(o.total_amount,0),o.notes,o.id
FROM sales_orders o
WHERE NOT EXISTS (SELECT 1 FROM sales_headers existing WHERE existing.sales_order_id=o.id);
