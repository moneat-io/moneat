-- Migration: Add log retention days
-- Description: Add separate retention policy for logs independent of event retention

ALTER TABLE pricing_tier_configs
ADD COLUMN log_retention_days INT;

-- Backfill with tier-specific log retention
-- FREE=3, PRO=30, TEAM=30, BUSINESS=90
UPDATE pricing_tier_configs
SET log_retention_days = CASE
    WHEN tier_name = 'FREE' THEN 3
    WHEN tier_name = 'PRO' THEN 30
    WHEN tier_name = 'TEAM' THEN 30
    WHEN tier_name = 'BUSINESS' THEN 90
    ELSE 3  -- Default to FREE tier retention
END;

-- Apply NOT NULL constraint after backfill
ALTER TABLE pricing_tier_configs
ALTER COLUMN log_retention_days SET NOT NULL;
