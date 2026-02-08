-- Align default retention policy with current pricing model.
-- FREE: 30 days, PRO/TEAM: 90 days.

UPDATE pricing_tier_configs
SET retention_days = 30
WHERE tier_name = 'FREE'
  AND retention_days <> 30;

UPDATE pricing_tier_configs
SET retention_days = 90
WHERE tier_name IN ('PRO', 'TEAM')
  AND retention_days <> 90;
