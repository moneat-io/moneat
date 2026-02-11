-- Create alert_notification_preferences table for unified alerting system
CREATE TABLE alert_notification_preferences (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    alert_source VARCHAR(50) NOT NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT true,
    slack_enabled BOOLEAN NOT NULL DEFAULT true,
    discord_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, organization_id, alert_source)
);

-- Create index for fast lookups by user and organization
CREATE INDEX idx_alert_notification_prefs_user_org 
ON alert_notification_preferences(user_id, organization_id);

-- Migrate existing notification_preferences data for ERROR_ALERT source
-- For each user with notification preferences, create ERROR_ALERT row
INSERT INTO alert_notification_preferences (user_id, organization_id, alert_source, email_enabled, slack_enabled, discord_enabled, created_at, updated_at)
SELECT 
    np.user_id,
    m.organization_id,
    'ERROR_ALERT' as alert_source,
    (np.issue_alerts AND np.error_alerts) as email_enabled,
    true as slack_enabled,  -- Default enabled
    true as discord_enabled, -- Default enabled
    COALESCE(np.created_at, now()) as created_at,
    COALESCE(np.updated_at, now()) as updated_at
FROM notification_preferences np
INNER JOIN memberships m ON m.user_id = np.user_id
WHERE np.project_id IS NULL  -- Only migrate global preferences
GROUP BY np.user_id, m.organization_id, np.issue_alerts, np.error_alerts, np.created_at, np.updated_at
ON CONFLICT (user_id, organization_id, alert_source) DO NOTHING;

-- Seed default preferences for all other alert sources for existing users
-- SYSTEM_ALERT, SYSTEM_DOWN, UPTIME_MONITOR
INSERT INTO alert_notification_preferences (user_id, organization_id, alert_source, email_enabled, slack_enabled, discord_enabled)
SELECT 
    m.user_id,
    m.organization_id,
    source.alert_source,
    true as email_enabled,
    true as slack_enabled,
    true as discord_enabled
FROM memberships m
CROSS JOIN (
    SELECT 'SYSTEM_ALERT' as alert_source
    UNION ALL SELECT 'SYSTEM_DOWN'
    UNION ALL SELECT 'UPTIME_MONITOR'
) source
WHERE NOT EXISTS (
    SELECT 1 FROM alert_notification_preferences anp
    WHERE anp.user_id = m.user_id 
    AND anp.organization_id = m.organization_id
    AND anp.alert_source = source.alert_source
);

-- Also seed ERROR_ALERT for users who don't have notification_preferences yet
INSERT INTO alert_notification_preferences (user_id, organization_id, alert_source, email_enabled, slack_enabled, discord_enabled)
SELECT 
    m.user_id,
    m.organization_id,
    'ERROR_ALERT' as alert_source,
    true as email_enabled,
    true as slack_enabled,
    true as discord_enabled
FROM memberships m
WHERE NOT EXISTS (
    SELECT 1 FROM alert_notification_preferences anp
    WHERE anp.user_id = m.user_id 
    AND anp.organization_id = m.organization_id
    AND anp.alert_source = 'ERROR_ALERT'
);
