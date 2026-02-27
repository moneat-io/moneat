-- Unified ingestion billing model: add DD Agent feature flags to pricing_tier_configs.
-- All features default to true (available on all tiers including Free).
-- Per-type count columns are kept for backward compatibility but are no longer enforced.

-- New feature flags for Datadog Agent features
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS dbm_enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS debugger_enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS k8s_monitoring_enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS data_streams_enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS sbom_enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS synthetics_enabled BOOLEAN NOT NULL DEFAULT true;

-- Enable profiling and network monitoring on all tiers (previously false on Free)
UPDATE pricing_tier_configs
SET profiling_enabled = true,
    network_monitoring_enabled = true
WHERE is_current = true;
