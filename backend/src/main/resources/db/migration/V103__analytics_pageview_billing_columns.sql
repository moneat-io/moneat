-- Add pending analytics pageview overage columns to subscriptions for Stripe meter billing
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pending_analytics_pageview_overage_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pending_analytics_pageview_batch_id VARCHAR(255);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pending_analytics_pageview_batch_units BIGINT NOT NULL DEFAULT 0;

-- Add execution count columns to org_usage_counters for visibility tracking
ALTER TABLE org_usage_counters ADD COLUMN IF NOT EXISTS used_synthetic_runs BIGINT NOT NULL DEFAULT 0;
ALTER TABLE org_usage_counters ADD COLUMN IF NOT EXISTS used_uptime_checks BIGINT NOT NULL DEFAULT 0;
ALTER TABLE org_usage_counters ADD COLUMN IF NOT EXISTS used_ai_tokens BIGINT NOT NULL DEFAULT 0;
