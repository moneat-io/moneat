-- Repair migration for environments where schema history drifted from actual tables.
-- Safe to run repeatedly.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20);

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS pending_meter_batch_id VARCHAR(255);

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS pending_meter_batch_units BIGINT NOT NULL DEFAULT 0;
