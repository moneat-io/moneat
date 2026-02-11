-- Add Discord integration tier flag to pricing_tier_configs
ALTER TABLE pricing_tier_configs ADD COLUMN discord_enabled BOOLEAN NOT NULL DEFAULT false;

-- Enable Discord for all tiers that currently have slack_enabled = true
UPDATE pricing_tier_configs SET discord_enabled = true WHERE slack_enabled = true;
