-- Existing PostgreSQL schema migration, run once with the application stopped.
-- Back up the DB first. This script does not create a fresh installation schema.
-- Run with psql -v ON_ERROR_STOP=1 -f db/migrations/001_common_contract.sql
-- Record successful execution in the deployment change log.
BEGIN;
ALTER TABLE items ALTER COLUMN price TYPE BIGINT USING price::BIGINT;
ALTER TABLE receipts ALTER COLUMN declared_total TYPE BIGINT USING declared_total::BIGINT;
ALTER TABLE items ADD COLUMN excluded_from_settlement BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE receipts ADD COLUMN request_id VARCHAR(64);
ALTER TABLE receipts ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE receipts ADD CONSTRAINT uk_receipt_room_request UNIQUE (room_id, request_id);
-- Do not infer exclusions, participants, payers or declared totals for old data.
-- NOT VALID preserves legacy rows for review but enforces these checks on new/updated rows.
ALTER TABLE items ADD CONSTRAINT ck_items_money_contract
  CHECK (price BETWEEN 1 AND 1000000 AND quantity BETWEEN 1 AND 999
         AND price * quantity::BIGINT <= 10000000) NOT VALID;
ALTER TABLE receipts ADD CONSTRAINT ck_receipts_declared_total_contract
  CHECK (declared_total IS NULL OR declared_total BETWEEN 1 AND 10000000) NOT VALID;
COMMIT;
