ALTER TABLE items ADD COLUMN IF NOT EXISTS replenishment_system varchar(20) NOT NULL DEFAULT 'NONE'
 CHECK (replenishment_system IN ('NONE','PURCHASE','PRODUCTION'));
ALTER TABLE items ADD COLUMN IF NOT EXISTS preferred_vendor_id bigint REFERENCES vendors(id);
CREATE TABLE IF NOT EXISTS order_planning_requests (
 request_key uuid PRIMARY KEY, signature text NOT NULL, result text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
