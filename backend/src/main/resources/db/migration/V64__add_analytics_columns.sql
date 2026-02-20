-- Add analytics-specific limits and retention columns to pricing_tier_configs
ALTER TABLE pricing_tier_configs
    ADD COLUMN max_analytics_sites INTEGER,
    ADD COLUMN analytics_retention_days INTEGER NOT NULL DEFAULT 1095,
    ADD COLUMN monthly_analytics_pageview_limit BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN analytics_pageview_overage_rate_cents_per_100k INTEGER NOT NULL DEFAULT 0;

-- Add analytics pageview usage tracking to org_usage_counters
ALTER TABLE org_usage_counters
    ADD COLUMN used_analytics_pageviews BIGINT NOT NULL DEFAULT 0;

-- Seed tier defaults for existing current tier configs
-- FREE: 1 site, 3yr retention, 10K pageviews, no overage
UPDATE pricing_tier_configs
SET max_analytics_sites = 1,
    analytics_retention_days = 1095,
    monthly_analytics_pageview_limit = 10000,
    analytics_pageview_overage_rate_cents_per_100k = 0
WHERE tier_name = 'FREE' AND is_current = true;

-- PRO: 5 sites, 3yr retention, 100K pageviews, $10/100K overage
UPDATE pricing_tier_configs
SET max_analytics_sites = 5,
    analytics_retention_days = 1095,
    monthly_analytics_pageview_limit = 100000,
    analytics_pageview_overage_rate_cents_per_100k = 1000
WHERE tier_name = 'PRO' AND is_current = true;

-- TEAM: 10 sites, 5yr retention, 1M pageviews, $10/100K overage
UPDATE pricing_tier_configs
SET max_analytics_sites = 10,
    analytics_retention_days = 1825,
    monthly_analytics_pageview_limit = 1000000,
    analytics_pageview_overage_rate_cents_per_100k = 1000
WHERE tier_name = 'TEAM' AND is_current = true;

-- BUSINESS: unlimited sites (NULL), 5yr retention, 10M pageviews, $10/100K overage
UPDATE pricing_tier_configs
SET max_analytics_sites = NULL,
    analytics_retention_days = 1825,
    monthly_analytics_pageview_limit = 10000000,
    analytics_pageview_overage_rate_cents_per_100k = 1000
WHERE tier_name = 'BUSINESS' AND is_current = true;
