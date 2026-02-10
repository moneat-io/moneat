-- Migration: Add GB-based pricing with yearly billing support
-- Description: Transform pricing from event-count to byte-based (GB) limits, add yearly billing interval

-- 1. Add GB-based limit and yearly pricing to pricing_tier_configs
ALTER TABLE pricing_tier_configs
ADD COLUMN monthly_gb_limit BIGINT DEFAULT 0,
ADD COLUMN yearly_price_cents INT DEFAULT 0,
ADD COLUMN stripe_yearly_base_price_id VARCHAR(255),
ADD COLUMN stripe_yearly_overage_price_id VARCHAR(255),
ADD COLUMN overage_rate_cents_per_gb INT DEFAULT 0;

-- 2. Add billing interval to subscriptions
ALTER TABLE subscriptions
ADD COLUMN billing_interval VARCHAR(20) DEFAULT 'monthly';

-- 3. Add used_bytes tracking to org_usage_counters
ALTER TABLE org_usage_counters
ADD COLUMN used_bytes BIGINT DEFAULT 0;

-- 4. Create index on billing_interval for faster queries
CREATE INDEX idx_subscriptions_billing_interval ON subscriptions(billing_interval);

-- 5. Update existing FREE tier config with GB limits (1 GB = 1073741824 bytes)
UPDATE pricing_tier_configs
SET monthly_gb_limit = 1073741824,  -- 1 GB
    yearly_price_cents = 0,
    overage_rate_cents_per_gb = 0
WHERE tier_name = 'FREE' AND is_current = true;

-- 6. Update existing PRO tier config with GB limits (50 GB)
UPDATE pricing_tier_configs
SET monthly_gb_limit = 53687091200,  -- 50 GB
    yearly_price_cents = 28800,  -- $288/yr ($24/mo effective)
    overage_rate_cents_per_gb = 40  -- $0.40/GB
WHERE tier_name = 'PRO' AND is_current = true;

-- 7. Update existing TEAM tier config with GB limits (200 GB)
UPDATE pricing_tier_configs
SET monthly_gb_limit = 214748364800,  -- 200 GB
    yearly_price_cents = 79200,  -- $792/yr ($66/mo effective)
    overage_rate_cents_per_gb = 40  -- $0.40/GB
WHERE tier_name = 'TEAM' AND is_current = true;

-- 8. Add new BUSINESS tier (if not exists)
INSERT INTO pricing_tier_configs (
    tier_name,
    version,
    monthly_unit_limit,
    monthly_error_limit,
    monthly_transaction_limit,
    monthly_replay_limit,
    monthly_feedback_limit,
    monthly_gb_limit,
    retention_days,
    max_projects,
    max_systems,
    monitor_interval_seconds,
    monthly_price_cents,
    yearly_price_cents,
    payg_enabled,
    payg_rate_micros_per_unit,
    overage_rate_cents_per_gb,
    is_current,
    created_at
)
SELECT
    'BUSINESS',
    1,
    9223372036854775807,  -- Max long (unlimited units)
    9223372036854775807,  -- Unlimited errors
    9223372036854775807,  -- Unlimited transactions
    9223372036854775807,  -- Unlimited replays
    9223372036854775807,  -- Unlimited feedback
    1099511627776,  -- 1 TB (1024 GB)
    180,  -- 180-day retention
    NULL,  -- Unlimited projects
    2147483647,  -- Unlimited systems (max int)
    10,  -- Custom monitor intervals (10s default)
    19900,  -- $199/mo
    199200,  -- $1,992/yr ($166/mo effective)
    true,  -- PAYG enabled
    400000,  -- $0.40/GB in micros (400000 microcents)
    40,  -- $0.40/GB overage
    true,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM pricing_tier_configs WHERE tier_name = 'BUSINESS'
);
