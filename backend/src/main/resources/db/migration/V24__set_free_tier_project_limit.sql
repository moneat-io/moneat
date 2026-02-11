-- Migration: Set project limit for FREE tier
-- Description: Enforce 3 project limit on FREE tier

UPDATE pricing_tier_configs
SET max_projects = 3
WHERE tier_name = 'FREE' AND is_current = true;
