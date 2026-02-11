-- Migration: Add per-tier trial days and normalize current pricing tier values
-- Description: Adds trial_days to pricing tiers, backfills canonical current-tier values, and enforces basic billing sanity checks.

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS trial_days INT;

UPDATE pricing_tier_configs
SET trial_days = CASE
    WHEN tier_name = 'FREE' THEN 0
    ELSE 14
END
WHERE trial_days IS NULL;

ALTER TABLE pricing_tier_configs
    ALTER COLUMN trial_days SET NOT NULL,
    ALTER COLUMN trial_days SET DEFAULT 14;

-- Canonical values for current tiers:
-- Free: $0/mo, $0/yr, 1GB, 3d retention
-- Pro: $29/mo, $288/yr, 50GB, 30d retention
-- Team: $79/mo, $792/yr, 200GB, 30d retention
-- Business: $199/mo, $1992/yr, 1TB, 90d retention
UPDATE pricing_tier_configs
SET
    monthly_unit_limit = CASE tier_name
        WHEN 'FREE' THEN 10000
        WHEN 'PRO' THEN 500000
        WHEN 'TEAM' THEN 5000000
        WHEN 'BUSINESS' THEN 9223372036854775807
        ELSE monthly_unit_limit
    END,
    monthly_error_limit = CASE tier_name
        WHEN 'FREE' THEN 10000
        WHEN 'PRO' THEN 500000
        WHEN 'TEAM' THEN 5000000
        WHEN 'BUSINESS' THEN 9223372036854775807
        ELSE monthly_error_limit
    END,
    monthly_transaction_limit = 0,
    monthly_replay_limit = CASE tier_name
        WHEN 'FREE' THEN 0
        WHEN 'PRO' THEN 50
        WHEN 'TEAM' THEN -1
        WHEN 'BUSINESS' THEN -1
        ELSE monthly_replay_limit
    END,
    monthly_feedback_limit = 0,
    monthly_gb_limit = CASE tier_name
        WHEN 'FREE' THEN 1073741824
        WHEN 'PRO' THEN 53687091200
        WHEN 'TEAM' THEN 214748364800
        WHEN 'BUSINESS' THEN 1099511627776
        ELSE monthly_gb_limit
    END,
    retention_days = CASE tier_name
        WHEN 'FREE' THEN 3
        WHEN 'PRO' THEN 30
        WHEN 'TEAM' THEN 30
        WHEN 'BUSINESS' THEN 90
        ELSE retention_days
    END,
    log_retention_days = CASE tier_name
        WHEN 'FREE' THEN 3
        WHEN 'PRO' THEN 30
        WHEN 'TEAM' THEN 30
        WHEN 'BUSINESS' THEN 90
        ELSE log_retention_days
    END,
    status_pages_enabled = true,
    status_page_custom_domain_enabled = true,
    session_replay_enabled = true,
    slack_enabled = true,
    incident_io_enabled = true,
    saml_enabled = CASE WHEN tier_name IN ('TEAM', 'BUSINESS') THEN true ELSE false END,
    oidc_enabled = CASE WHEN tier_name IN ('TEAM', 'BUSINESS') THEN true ELSE false END,
    priority_support_enabled = CASE WHEN tier_name = 'BUSINESS' THEN true ELSE false END,
    sla_enabled = CASE WHEN tier_name = 'BUSINESS' THEN true ELSE false END,
    custom_retention_enabled = CASE WHEN tier_name = 'BUSINESS' THEN true ELSE false END,
    max_projects = CASE tier_name
        WHEN 'FREE' THEN 3
        ELSE NULL
    END,
    max_systems = CASE tier_name
        WHEN 'FREE' THEN 3
        WHEN 'PRO' THEN 10
        WHEN 'TEAM' THEN 25
        WHEN 'BUSINESS' THEN 2147483647
        ELSE max_systems
    END,
    monitor_interval_seconds = CASE tier_name
        WHEN 'FREE' THEN 60
        WHEN 'PRO' THEN 30
        WHEN 'TEAM' THEN 10
        WHEN 'BUSINESS' THEN 10
        ELSE monitor_interval_seconds
    END,
    monthly_price_cents = CASE tier_name
        WHEN 'FREE' THEN 0
        WHEN 'PRO' THEN 2900
        WHEN 'TEAM' THEN 7900
        WHEN 'BUSINESS' THEN 19900
        ELSE monthly_price_cents
    END,
    yearly_price_cents = CASE tier_name
        WHEN 'FREE' THEN 0
        WHEN 'PRO' THEN 28800
        WHEN 'TEAM' THEN 79200
        WHEN 'BUSINESS' THEN 199200
        ELSE yearly_price_cents
    END,
    trial_days = CASE tier_name
        WHEN 'FREE' THEN 0
        ELSE 14
    END,
    payg_enabled = CASE WHEN tier_name = 'FREE' THEN false ELSE true END,
    payg_rate_micros_per_unit = CASE WHEN tier_name = 'FREE' THEN 0 ELSE 400000 END,
    overage_rate_cents_per_gb = CASE WHEN tier_name = 'FREE' THEN 0 ELSE 40 END
WHERE is_current = true
  AND tier_name IN ('FREE', 'PRO', 'TEAM', 'BUSINESS');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pricing_tier_trial_nonnegative'
    ) THEN
        ALTER TABLE pricing_tier_configs
            ADD CONSTRAINT chk_pricing_tier_trial_nonnegative
            CHECK (trial_days >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pricing_tier_prices_nonnegative'
    ) THEN
        ALTER TABLE pricing_tier_configs
            ADD CONSTRAINT chk_pricing_tier_prices_nonnegative
            CHECK (monthly_price_cents >= 0 AND yearly_price_cents >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_pricing_tier_paid_has_gb_limit'
    ) THEN
        ALTER TABLE pricing_tier_configs
            ADD CONSTRAINT chk_pricing_tier_paid_has_gb_limit
            CHECK (
                CASE
                    WHEN monthly_price_cents > 0 THEN monthly_gb_limit > 0
                    ELSE true
                END
            );
    END IF;
END$$;
