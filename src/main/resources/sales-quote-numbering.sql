CREATE SEQUENCE IF NOT EXISTS sales_quote_document_no_seq;
SELECT setval('sales_quote_document_no_seq', greatest(coalesce(
 (SELECT max(substring(quote_no from 4)::bigint) FROM sales_headers WHERE quote_no ~ '^SQ-[0-9]{1,15}$'),0),1),
 EXISTS(SELECT 1 FROM sales_headers WHERE quote_no ~ '^SQ-[0-9]{1,15}$' AND substring(quote_no from 4)::bigint > 0));
CREATE OR REPLACE FUNCTION erp_next_sales_quote_no() RETURNS text LANGUAGE plpgsql AS $$
DECLARE n bigint; candidate text;
BEGIN
 LOOP
  n := nextval('sales_quote_document_no_seq');
  candidate := 'SQ-' || lpad(n::text, greatest(3,length(n::text)), '0');
  IF NOT EXISTS(SELECT 1 FROM sales_headers WHERE quote_no=candidate) THEN RETURN candidate; END IF;
 END LOOP;
END $$;