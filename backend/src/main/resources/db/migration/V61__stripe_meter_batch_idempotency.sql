-- Add durable Stripe meter batch state to avoid duplicate charging on retries.
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS pending_meter_batch_id VARCHAR(255);

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS pending_meter_batch_units BIGINT NOT NULL DEFAULT 0;
