ALTER TABLE pricing_tier_configs
ADD COLUMN oncall_per_user_monthly_cents INTEGER DEFAULT 0,
ADD COLUMN oncall_per_user_yearly_cents INTEGER DEFAULT 0,
ADD COLUMN oncall_enabled BOOLEAN DEFAULT FALSE,
ADD COLUMN stripe_oncall_price_id VARCHAR(255),
ADD COLUMN stripe_oncall_yearly_price_id VARCHAR(255);

ALTER TABLE subscriptions
ADD COLUMN oncall_seats INTEGER DEFAULT 0,
ADD COLUMN stripe_oncall_item_id VARCHAR(255);

UPDATE pricing_tier_configs
SET oncall_enabled = TRUE
WHERE tier_name IN ('PRO', 'TEAM', 'BUSINESS');
