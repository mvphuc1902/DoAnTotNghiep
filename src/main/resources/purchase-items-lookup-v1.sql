DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        JOIN pg_attribute source_column
          ON source_column.attrelid = constraint_row.conrelid
         AND source_column.attnum = constraint_row.conkey[1]
        JOIN pg_attribute target_column
          ON target_column.attrelid = constraint_row.confrelid
         AND target_column.attnum = constraint_row.confkey[1]
        WHERE constraint_row.contype = 'f'
          AND constraint_row.conrelid = 'public.purchase_lines'::regclass
          AND constraint_row.confrelid = 'public.items'::regclass
          AND source_column.attname = 'item_id'
          AND target_column.attname = 'id'
          AND array_length(constraint_row.conkey, 1) = 1
          AND array_length(constraint_row.confkey, 1) = 1
    ) THEN
        ALTER TABLE public.purchase_lines
            ADD CONSTRAINT purchase_lines_item_id_fkey
            FOREIGN KEY (item_id) REFERENCES public.items(id) NOT VALID;
    END IF;
END $$;
