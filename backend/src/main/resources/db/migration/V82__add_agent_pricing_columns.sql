-- Add APM, metrics, profiling, and infrastructure monitoring pricing columns.
-- Aligns with competitive pricing plan for Datadog agent features.

-- pricing_tier_configs: APM span limits and overage
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS monthly_apm_span_limit BIGINT NOT NULL DEFAULT 0;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS apm_span_overage_rate_cents_per_1m INTEGER NOT NULL DEFAULT 0;

-- pricing_tier_configs: custom metric limits and overage
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS monthly_custom_metric_limit BIGINT NOT NULL DEFAULT 0;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS custom_metric_overage_rate_cents_per_100k INTEGER NOT NULL DEFAULT 0;

-- pricing_tier_configs: infrastructure monitoring limits
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS max_hosts INTEGER;

-- pricing_tier_configs: feature flags for agent features
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS profiling_enabled BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS network_monitoring_enabled BOOLEAN NOT NULL DEFAULT false;

-- org_usage_counters: APM and metrics tracking
ALTER TABLE org_usage_counters
    ADD COLUMN IF NOT EXISTS used_apm_spans BIGINT NOT NULL DEFAULT 0;

ALTER TABLE org_usage_counters
    ADD COLUMN IF NOT EXISTS used_custom_metrics BIGINT NOT NULL DEFAULT 0;

-- Backfill tier limits per competitive pricing plan
UPDATE pricing_tier_configs
SET monthly_apm_span_limit = 500000,
    apm_span_overage_rate_cents_per_1m = 0,
    monthly_custom_metric_limit = 100000,
    custom_metric_overage_rate_cents_per_100k = 0,
    max_hosts = 3,
    profiling_enabled = false,
    network_monitoring_enabled = false
WHERE tier_name = 'FREE' AND is_current = true;

UPDATE pricing_tier_configs
SET monthly_apm_span_limit = 10000000,
    apm_span_overage_rate_cents_per_1m = 30,
    monthly_custom_metric_limit = 1000000,
    custom_metric_overage_rate_cents_per_100k = 50,
    max_hosts = NULL,
    profiling_enabled = true,
    network_monitoring_enabled = true
WHERE tier_name = 'PRO' AND is_current = true;

UPDATE pricing_tier_configs
SET monthly_apm_span_limit = 100000000,
    apm_span_overage_rate_cents_per_1m = 30,
    monthly_custom_metric_limit = 10000000,
    custom_metric_overage_rate_cents_per_100k = 50,
    max_hosts = NULL,
    profiling_enabled = true,
    network_monitoring_enabled = true
WHERE tier_name = 'TEAM' AND is_current = true;

UPDATE pricing_tier_configs
SET monthly_apm_span_limit = 9223372036854775807,
    apm_span_overage_rate_cents_per_1m = 30,
    monthly_custom_metric_limit = 9223372036854775807,
    custom_metric_overage_rate_cents_per_100k = 50,
    max_hosts = NULL,
    profiling_enabled = true,
    network_monitoring_enabled = true
WHERE tier_name = 'BUSINESS' AND is_current = true;
