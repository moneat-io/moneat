-- Add pending overage tracking columns for APM spans and custom metrics
-- These are flushed to Stripe meter events (separate from the main unit meter)
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS pending_apm_span_overage_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_apm_span_batch_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS pending_apm_span_batch_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_custom_metric_overage_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_custom_metric_batch_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS pending_custom_metric_batch_units BIGINT NOT NULL DEFAULT 0;
