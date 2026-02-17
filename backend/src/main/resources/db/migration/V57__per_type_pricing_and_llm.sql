-- Per-type pricing: LLM event limits, per-type retention, per-type overage rates.
-- Aligns with competitor analysis: 300 replays for all tiers, LLM observability billing, flexible overages.

-- pricing_tier_configs: LLM event limits
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS monthly_llm_event_limit BIGINT NOT NULL DEFAULT 0;

-- pricing_tier_configs: per-type retention
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS replay_retention_days INTEGER NOT NULL DEFAULT 0;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS llm_retention_days INTEGER NOT NULL DEFAULT 0;

-- pricing_tier_configs: per-type overage rates
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS error_overage_rate_cents_per_1k INTEGER NOT NULL DEFAULT 0;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS replay_overage_rate_cents_per_gb INTEGER NOT NULL DEFAULT 0;

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS llm_overage_rate_cents_per_1k INTEGER NOT NULL DEFAULT 0;

-- org_usage_counters: LLM event tracking
ALTER TABLE org_usage_counters
    ADD COLUMN IF NOT EXISTS used_llm_events BIGINT NOT NULL DEFAULT 0;

-- Backfill existing tier configs with proposed values
UPDATE pricing_tier_configs
SET
    monthly_llm_event_limit = CASE tier_name
        WHEN 'FREE' THEN 1000
        WHEN 'PRO' THEN 10000
        WHEN 'TEAM' THEN 100000
        WHEN 'BUSINESS' THEN 9223372036854775807
        ELSE 0
    END,
    replay_retention_days = CASE tier_name
        WHEN 'FREE' THEN 3
        WHEN 'PRO' THEN 14
        WHEN 'TEAM' THEN 30
        WHEN 'BUSINESS' THEN 90
        ELSE COALESCE(retention_days, 30)
    END,
    llm_retention_days = COALESCE(retention_days, 30),
    error_overage_rate_cents_per_1k = CASE WHEN tier_name = 'FREE' THEN 0 ELSE 10 END,
    replay_overage_rate_cents_per_gb = CASE WHEN tier_name = 'FREE' THEN 0 ELSE 40 END,
    llm_overage_rate_cents_per_1k = CASE WHEN tier_name = 'FREE' THEN 0 ELSE 100 END,
    monthly_replay_limit = 300
WHERE tier_name IN ('FREE', 'PRO', 'TEAM', 'BUSINESS');
