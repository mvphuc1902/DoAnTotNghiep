CREATE SEQUENCE warehouse_operation_no_seq;
CREATE TABLE warehouse_config (
 location_id bigint PRIMARY KEY REFERENCES locations(id), enabled_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE warehouse_zones (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, location_id bigint NOT NULL REFERENCES locations(id),
 code varchar(30) NOT NULL, name varchar(150) NOT NULL, UNIQUE(location_id,code),UNIQUE(id,location_id)
);
CREATE TABLE warehouse_bins (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,location_id bigint NOT NULL REFERENCES locations(id),
 zone_id bigint NOT NULL,code varchar(30) NOT NULL,
 bin_type varchar(20) NOT NULL CHECK(bin_type IN ('RECEIVING','STORAGE','PICKING','SHIPPING','QC','QUARANTINE','RETURN')),
 capacity numeric(18,3) CHECK(capacity>0),blocked boolean NOT NULL DEFAULT false,
 UNIQUE(location_id,code),UNIQUE(id,location_id),FOREIGN KEY(zone_id,location_id) REFERENCES warehouse_zones(id,location_id)
);
CREATE TABLE warehouse_operations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,document_no varchar(50) NOT NULL UNIQUE,
 operation_type varchar(20) NOT NULL CHECK(operation_type IN ('PUTAWAY','PICK','MOVE','TRANSFER','COUNT')),
 item_id bigint NOT NULL REFERENCES items(id),source_bin_id bigint NOT NULL REFERENCES warehouse_bins(id),
 destination_bin_id bigint REFERENCES warehouse_bins(id),quantity numeric(18,3) NOT NULL CHECK(quantity>=0),
 status varchar(20) NOT NULL CHECK(status IN ('REGISTERED','PENDING','APPROVED','POSTED')),
 expected_quantity numeric(18,3),counted_quantity numeric(18,3),snapshot_entry_id bigint,
 reason text NOT NULL,created_by varchar(100) NOT NULL,approved_by varchar(100),
 request_key uuid NOT NULL UNIQUE,signature text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),posted_at timestamptz
);
CREATE TABLE warehouse_bin_inventory (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,location_id bigint NOT NULL,bin_id bigint NOT NULL,
 item_id bigint NOT NULL REFERENCES items(id),quantity numeric(18,3) NOT NULL CHECK(quantity>=0),
 UNIQUE(bin_id,item_id),FOREIGN KEY(bin_id,location_id) REFERENCES warehouse_bins(id,location_id)
);
CREATE TABLE warehouse_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,location_id bigint NOT NULL,bin_id bigint NOT NULL,
 item_id bigint NOT NULL REFERENCES items(id),quantity numeric(18,3) NOT NULL CHECK(quantity<>0),
 entry_type varchar(30) NOT NULL,operation_id bigint REFERENCES warehouse_operations(id),
 inventory_entry_id bigint REFERENCES inventory_ledger(id),created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(bin_id,location_id) REFERENCES warehouse_bins(id,location_id)
);
CREATE INDEX warehouse_entry_subject ON warehouse_entries(bin_id,item_id,id);
CREATE OR REPLACE FUNCTION warehouse_apply_entry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b warehouse_bins%ROWTYPE; existing numeric;
BEGIN
 PERFORM pg_advisory_xact_lock(7821348);
 SELECT * INTO STRICT b FROM warehouse_bins WHERE id=NEW.bin_id FOR UPDATE;
 IF b.blocked THEN RAISE EXCEPTION 'WAREHOUSE: Bin đang khóa.' USING ERRCODE='23514'; END IF;
 IF NEW.quantity>0 AND b.capacity IS NOT NULL THEN
  SELECT coalesce(sum(quantity),0) INTO existing FROM warehouse_bin_inventory WHERE bin_id=b.id;
  IF existing+NEW.quantity>b.capacity THEN RAISE EXCEPTION 'WAREHOUSE: Vượt sức chứa Bin.' USING ERRCODE='23514'; END IF;
 END IF;
 INSERT INTO warehouse_bin_inventory(location_id,bin_id,item_id,quantity) VALUES(NEW.location_id,NEW.bin_id,NEW.item_id,0) ON CONFLICT(bin_id,item_id) DO NOTHING;
 UPDATE warehouse_bin_inventory SET quantity=quantity+NEW.quantity WHERE bin_id=NEW.bin_id AND item_id=NEW.item_id AND quantity+NEW.quantity>=0;
 IF NOT FOUND THEN RAISE EXCEPTION 'WAREHOUSE: Không đủ số lượng tại Bin nguồn.' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER warehouse_apply_entry BEFORE INSERT ON warehouse_entries FOR EACH ROW EXECUTE FUNCTION warehouse_apply_entry();
CREATE TRIGGER warehouse_entry_immutable BEFORE UPDATE OR DELETE ON warehouse_entries FOR EACH ROW EXECUTE FUNCTION purchase_posted_immutable();
CREATE OR REPLACE FUNCTION warehouse_sync_inventory() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE delta numeric; remaining numeric; take_qty numeric; target bigint; binrow record;
BEGIN
 IF NEW.item_id IS NULL OR NOT EXISTS(SELECT 1 FROM warehouse_config WHERE location_id=NEW.location_id) THEN RETURN NEW; END IF;
 PERFORM pg_advisory_xact_lock(7821348);
 delta=NEW.quantity_in-NEW.quantity_out;
 IF delta=0 THEN RETURN NEW; END IF;
 target=nullif(current_setting('erp.warehouse_bin',true),'')::bigint;
 IF target IS NOT NULL THEN
  IF NOT EXISTS(SELECT 1 FROM warehouse_bins WHERE id=target AND location_id=NEW.location_id) THEN RAISE EXCEPTION 'WAREHOUSE: Bin không thuộc kho.'; END IF;
  INSERT INTO warehouse_entries(location_id,bin_id,item_id,quantity,entry_type,inventory_entry_id,operation_id)
  VALUES(NEW.location_id,target,NEW.item_id,delta,NEW.document_type,NEW.id,CASE WHEN NEW.document_type IN ('WH_TRANSFER','WH_COUNT') THEN NEW.document_id ELSE NULL END);
 ELSIF delta>0 THEN
  SELECT id INTO STRICT target FROM warehouse_bins WHERE location_id=NEW.location_id AND code='RECEIVE';
  INSERT INTO warehouse_entries(location_id,bin_id,item_id,quantity,entry_type,inventory_entry_id)
  VALUES(NEW.location_id,target,NEW.item_id,delta,NEW.document_type,NEW.id);
 ELSE
  remaining=-delta;
  FOR binrow IN SELECT s.bin_id,s.quantity FROM warehouse_bin_inventory s JOIN warehouse_bins b ON b.id=s.bin_id
   WHERE s.location_id=NEW.location_id AND s.item_id=NEW.item_id AND s.quantity>0 AND NOT b.blocked
   AND (CASE WHEN NEW.document_type='sales_shipments' THEN b.bin_type='SHIPPING' ELSE b.bin_type IN ('STORAGE','PICKING','SHIPPING') END)
   ORDER BY s.bin_id LOOP
   take_qty=least(remaining,binrow.quantity);
   INSERT INTO warehouse_entries(location_id,bin_id,item_id,quantity,entry_type,inventory_entry_id)
   VALUES(NEW.location_id,binrow.bin_id,NEW.item_id,-take_qty,NEW.document_type,NEW.id);
   remaining=remaining-take_qty;EXIT WHEN remaining=0;
  END LOOP;
  IF remaining>0 THEN RAISE EXCEPTION 'WAREHOUSE: Không đủ hàng ở Bin xuất. Put-away trước khi sử dụng; Pick sang SHIPPING trước khi giao Sales.' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER warehouse_sync_inventory AFTER INSERT ON inventory_ledger FOR EACH ROW EXECUTE FUNCTION warehouse_sync_inventory();
CREATE OR REPLACE FUNCTION warehouse_unavailable_quantity(item bigint,warehouse bigint) RETURNS numeric LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(s.quantity),0) FROM warehouse_bin_inventory s JOIN warehouse_bins b ON b.id=s.bin_id
 WHERE s.item_id=item AND s.location_id=warehouse AND (b.blocked OR b.bin_type IN ('QC','QUARANTINE','RETURN'))
$$;
CREATE TABLE warehouse_gl_links (
 operation_id bigint NOT NULL REFERENCES warehouse_operations(id),ledger_id bigint NOT NULL UNIQUE REFERENCES general_ledger(id),
 PRIMARY KEY(operation_id,ledger_id)
);
CREATE OR REPLACE FUNCTION warehouse_gl_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM warehouse_gl_links WHERE ledger_id=OLD.id) THEN
  RAISE EXCEPTION 'WAREHOUSE: Bút toán điều chỉnh đã Post không được sửa/xóa trực tiếp.' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' THEN RETURN NEW; END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER warehouse_gl_guard BEFORE UPDATE OR DELETE ON general_ledger FOR EACH ROW EXECUTE FUNCTION warehouse_gl_guard();
