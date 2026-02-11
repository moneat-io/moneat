-- Migration: Add pricing feature flags and align retention values
-- Description: Adds per-tier pricing visibility flags and updates current tier retention values.

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS status_pages_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS status_page_custom_domain_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS session_replay_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS slack_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS incident_io_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS saml_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS oidc_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS priority_support_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS sla_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS custom_retention_enabled BOOLEAN NOT NULL DEFAULT false;

UPDATE pricing_tier_configs
SET retention_days = CASE tier_name
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
    custom_retention_enabled = CASE WHEN tier_name = 'BUSINESS' THEN true ELSE false END
WHERE is_current = true;
