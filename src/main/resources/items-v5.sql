CREATE OR REPLACE FUNCTION erp_item_inventory() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.item_category = 'INVENTORY' THEN
        INSERT INTO inventory(item_id, location_id, quantity, average_cost)
        VALUES (NEW.id, erp_default_location(), 0, coalesce(NEW.unit_cost, 0));
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS erp_item_inventory ON items;
CREATE TRIGGER erp_item_inventory AFTER INSERT ON items
FOR EACH ROW EXECUTE FUNCTION erp_item_inventory();

DO $$
DECLARE
    reference record;
    referenced_max bigint;
    maximum_id bigint;
    sequence_name text;
BEGIN
    SELECT coalesce(max(id), 0) INTO maximum_id FROM items;
    FOR reference IN
        SELECT * FROM (VALUES
            ('inventory', 'item_id'), ('inventory_ledger', 'item_id'),
            ('sales_order_lines', 'item_id'), ('sales_quote_lines', 'item_id'),
            ('sales_lines', 'item_id'), ('purchase_lines', 'item_id'),
            ('purchase_receipt_lines', 'item_id'),
            ('sales_invoice_lines', 'item_id'), ('sales_return_lines', 'item_id'),
            ('item_bom', 'parent_item_id'), ('item_bom', 'component_item_id'),
            ('production_orders', 'item_id'), ('production_components', 'component_item_id')
        ) AS refs(table_name, column_name)
    LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = reference.table_name
                     AND column_name = reference.column_name) THEN
            EXECUTE format('SELECT coalesce(max(%I), 0) FROM public.%I',
                           reference.column_name, reference.table_name)
            INTO referenced_max;
            maximum_id = greatest(maximum_id, referenced_max);
        END IF;
    END LOOP;
    sequence_name = pg_get_serial_sequence('public.items', 'id');
    IF sequence_name IS NULL THEN
        RAISE EXCEPTION 'Items ID sequence is missing.';
    END IF;
    PERFORM setval(sequence_name::regclass, maximum_id + 1, false);
END $$;

INSERT INTO inventory(item_id, location_id, quantity, average_cost)
SELECT item.id, erp_default_location(), 0, coalesce(item.unit_cost, 0)
FROM items item
WHERE item.item_category = 'INVENTORY'
  AND NOT EXISTS (SELECT 1 FROM inventory stock WHERE stock.item_id = item.id)
ON CONFLICT (item_id, location_id) DO NOTHING;

UPDATE items item SET quantity = coalesce(stock.total, 0)
FROM (SELECT item_id, sum(quantity) AS total FROM inventory
      WHERE item_id IS NOT NULL GROUP BY item_id) stock
WHERE item.id = stock.item_id;
