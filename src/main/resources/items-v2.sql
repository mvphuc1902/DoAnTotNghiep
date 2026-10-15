ALTER TABLE items
    ADD COLUMN IF NOT EXISTS item_type varchar(20) NOT NULL DEFAULT 'PURCHASED';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'items'::regclass AND conname = 'items_item_type_check'
    ) THEN
        ALTER TABLE items
            ADD CONSTRAINT items_item_type_check
            CHECK (item_type IN ('PURCHASED', 'MANUFACTURED'));
    END IF;
END $$;
