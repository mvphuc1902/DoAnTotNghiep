DO $$
DECLARE
    temporary_prefix text := '__ERP_SQ_RENUMBER__';
BEGIN
    LOOP
        EXIT WHEN NOT EXISTS (
            SELECT 1
            FROM sales_headers target
            JOIN sales_headers existing
              ON existing.quote_no = temporary_prefix || target.id::text
            WHERE target.document_type = 'QUOTE'
              AND existing.id <> target.id
        );
        temporary_prefix := temporary_prefix || '_';
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM sales_headers header
        WHERE header.document_type <> 'QUOTE'
          AND header.quote_no ~ '^SQ-[0-9]{1,15}$'
          AND substring(header.quote_no FROM 4)::numeric <= (
              SELECT count(*) FROM sales_headers WHERE document_type = 'QUOTE'
          )
    ) THEN
        RAISE EXCEPTION 'Cannot renumber Sales Quotes: target SQ number is already used by another document.';
    END IF;

    UPDATE sales_headers
    SET quote_no = temporary_prefix || id::text
    WHERE document_type = 'QUOTE';

    WITH numbered_quotes AS (
        SELECT id, 'SQ-' || lpad(row_number() OVER (ORDER BY id)::text, 3, '0') AS document_no
        FROM sales_headers
        WHERE document_type = 'QUOTE'
    )
    UPDATE sales_headers header
    SET quote_no = numbered_quotes.document_no
    FROM numbered_quotes
    WHERE header.id = numbered_quotes.id;
END $$;
