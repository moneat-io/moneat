-- Billing foundation: tier configs, quota counters, webhook idempotency, and subscription extensions.

CREATE TABLE IF NOT EXISTS pricing_tier_configs (
    id SERIAL PRIMARY KEY,
    tier_name VARCHAR(50) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    monthly_unit_limit BIGINT NOT NULL,
    retention_days INT NOT NULL,
    max_projects INT,
    max_systems INT NOT NULL,
    monitor_interval_seconds INT NOT NULL,
    monthly_price_cents INT NOT NULL,
    payg_enabled BOOLEAN NOT NULL DEFAULT false,
    payg_rate_micros_per_unit BIGINT NOT NULL DEFAULT 0,
    stripe_base_price_id VARCHAR(255),
    stripe_overage_price_id VARCHAR(255),
    is_current BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tier_name, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pricing_tier_current_per_name
    ON pricing_tier_configs (tier_name)
    WHERE is_current = true;

ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pricing_tier_config_id INT REFERENCES pricing_tier_configs(id);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS payg_budget_cents INT NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS payg_used_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS payg_used_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pending_meter_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS stripe_base_item_id VARCHAR(255);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS stripe_overage_item_id VARCHAR(255);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS billing_grace_until TIMESTAMP;

CREATE TABLE IF NOT EXISTS org_usage_counters (
    id SERIAL PRIMARY KEY,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    used_units BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, period_start)
);

CREATE INDEX IF NOT EXISTS idx_org_usage_counters_org_period
    ON org_usage_counters (organization_id, period_start);

CREATE TABLE IF NOT EXISTS quota_notifications_sent (
    id SERIAL PRIMARY KEY,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, period_start, notification_type)
);

CREATE INDEX IF NOT EXISTS idx_quota_notifications_org_period
    ON quota_notifications_sent (organization_id, period_start);

CREATE TABLE IF NOT EXISTS stripe_webhook_events (
    id SERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO pricing_tier_configs (
    tier_name,
    version,
    monthly_unit_limit,
    retention_days,
    max_projects,
    max_systems,
    monitor_interval_seconds,
    monthly_price_cents,
    payg_enabled,
    payg_rate_micros_per_unit,
    stripe_base_price_id,
    stripe_overage_price_id,
    is_current
) VALUES
    ('FREE', 1, 10000, 7, 1, 1, 60, 0, false, 0, NULL, NULL, true),
    ('PRO', 1, 500000, 30, NULL, 5, 15, 1900, true, 10, NULL, NULL, true),
    ('TEAM', 1, 5000000, 90, NULL, 25, 10, 4900, true, 10, NULL, NULL, true)
ON CONFLICT (tier_name, version) DO NOTHING;

-- Attach active/trialing/past_due subscriptions to current tier config.
UPDATE subscriptions s
SET pricing_tier_config_id = pt.id
FROM pricing_tier_configs pt
WHERE s.pricing_tier_config_id IS NULL
  AND pt.is_current = true
  AND UPPER(s.plan) = UPPER(pt.tier_name)
  AND s.status IN ('active', 'trialing', 'past_due');

-- Fallback to FREE when plan text is unexpected.
UPDATE subscriptions s
SET pricing_tier_config_id = pt.id
FROM pricing_tier_configs pt
WHERE s.pricing_tier_config_id IS NULL
  AND pt.is_current = true
  AND pt.tier_name = 'FREE'
  AND s.status IN ('active', 'trialing', 'past_due');

-- Backfill one active FREE subscription for orgs with no active/trialing/past_due row.
INSERT INTO subscriptions (
    organization_id,
    plan,
    status,
    current_period_start,
    current_period_end,
    pricing_tier_config_id,
    payg_budget_cents,
    payg_used_units,
    payg_used_micros,
    pending_meter_units
)
SELECT
    o.id,
    'free',
    'active',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP + INTERVAL '1 month',
    pt.id,
    0,
    0,
    0,
    0
FROM organizations o
JOIN pricing_tier_configs pt
    ON pt.tier_name = 'FREE' AND pt.is_current = true
WHERE NOT EXISTS (
    SELECT 1
    FROM subscriptions s
    WHERE s.organization_id = o.id
      AND s.status IN ('active', 'trialing', 'past_due')
);
