ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS monthly_infra_metric_series_hour_limit BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS infra_metric_overage_rate_cents_per_100k_series_hours INTEGER NOT NULL DEFAULT 0;

ALTER TABLE org_usage_counters
    ADD COLUMN IF NOT EXISTS used_infra_metric_series_hours BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_infra_metric_bytes BIGINT NOT NULL DEFAULT 0;

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS pending_infra_metric_overage_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_infra_metric_batch_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS pending_infra_metric_batch_units BIGINT NOT NULL DEFAULT 0;

UPDATE pricing_tier_configs
SET monthly_infra_metric_series_hour_limit = 5000000,
    infra_metric_overage_rate_cents_per_100k_series_hours = 0
WHERE tier_name = 'FREE'
  AND monthly_infra_metric_series_hour_limit = 0;

UPDATE pricing_tier_configs
SET monthly_infra_metric_series_hour_limit = 50000000,
    infra_metric_overage_rate_cents_per_100k_series_hours = 10
WHERE tier_name = 'PRO'
  AND monthly_infra_metric_series_hour_limit = 0;

UPDATE pricing_tier_configs
SET monthly_infra_metric_series_hour_limit = 250000000,
    infra_metric_overage_rate_cents_per_100k_series_hours = 10
WHERE tier_name = 'TEAM'
  AND monthly_infra_metric_series_hour_limit = 0;

UPDATE pricing_tier_configs
SET monthly_infra_metric_series_hour_limit = 9223372036854775807,
    infra_metric_overage_rate_cents_per_100k_series_hours = 10
WHERE tier_name = 'BUSINESS'
  AND monthly_infra_metric_series_hour_limit = 0;
