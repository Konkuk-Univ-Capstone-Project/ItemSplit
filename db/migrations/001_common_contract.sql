-- Existing PostgreSQL schema migration; stop all application instances and back up first.
-- Safe to rerun, including after Hibernate partially updated the schema.
-- This script does not create a fresh installation schema.
-- Run with psql -v ON_ERROR_STOP=1 -f db/migrations/001_common_contract.sql
-- Record successful execution in the deployment change log.
BEGIN;
ALTER TABLE items ALTER COLUMN price TYPE BIGINT USING price::BIGINT;
ALTER TABLE receipts ALTER COLUMN declared_total TYPE BIGINT USING declared_total::BIGINT;
ALTER TABLE items ADD COLUMN IF NOT EXISTS excluded_from_settlement BOOLEAN DEFAULT FALSE;
-- Repair a nullable column from a partial migration without changing explicit exclusions.
UPDATE items SET excluded_from_settlement = FALSE WHERE excluded_from_settlement IS NULL;
ALTER TABLE items ALTER COLUMN excluded_from_settlement SET DEFAULT FALSE;
ALTER TABLE items ALTER COLUMN excluded_from_settlement SET NOT NULL;
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
ALTER TABLE receipts ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

-- PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS. Scope each lookup to its table.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'receipts'::regclass AND conname = 'uk_receipt_room_request') THEN
    ALTER TABLE receipts ADD CONSTRAINT uk_receipt_room_request UNIQUE (room_id, request_id);
  END IF;
  -- Do not infer participants, payers or declared totals for old data.
  -- NOT VALID preserves legacy rows for review but checks new/updated rows.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'items'::regclass AND conname = 'ck_items_money_contract') THEN
    ALTER TABLE items ADD CONSTRAINT ck_items_money_contract
      CHECK (price BETWEEN 1 AND 1000000 AND quantity BETWEEN 1 AND 999
             AND price * quantity::BIGINT <= 10000000) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'receipts'::regclass AND conname = 'ck_receipts_declared_total_contract') THEN
    ALTER TABLE receipts ADD CONSTRAINT ck_receipts_declared_total_contract
      CHECK (declared_total IS NULL OR declared_total BETWEEN 1 AND 10000000) NOT VALID;
  END IF;
END $$;
COMMIT;
