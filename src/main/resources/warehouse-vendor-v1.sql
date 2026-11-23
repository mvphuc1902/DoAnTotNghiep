-- Preserve historical records; enforce warehouse references for new writes.
DO $$
DECLARE t text; constraint_name text; missing boolean;
BEGIN
 FOREACH t IN ARRAY ARRAY['purchase_headers','purchase_lines','purchase_receipts','purchase_receipt_lines','sales_shipments','sales_shipment_lines'] LOOP
  constraint_name := t || '_warehouse_code_fk';
  IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.'||t) AND conname=constraint_name) THEN
   EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I FOREIGN KEY(location_code) REFERENCES public.locations(code) ON UPDATE RESTRICT ON DELETE RESTRICT NOT VALID',t,constraint_name);
  END IF;
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I d WHERE d.location_code IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.locations l WHERE l.code=d.location_code))',t) INTO missing;
  IF NOT missing THEN EXECUTE format('ALTER TABLE public.%I VALIDATE CONSTRAINT %I',t,constraint_name); END IF;
 END LOOP;
END $$;
