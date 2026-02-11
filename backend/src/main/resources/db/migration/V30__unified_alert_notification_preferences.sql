CREATE TABLE IF NOT EXISTS alert_notification_preferences (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    alert_source VARCHAR(50) NOT NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT true,
    slack_enabled BOOLEAN NOT NULL DEFAULT true,
    discord_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, organization_id, alert_source),
    CHECK (alert_source IN ('SYSTEM_ALERT', 'SYSTEM_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT'))
);

CREATE INDEX IF NOT EXISTS idx_alert_notification_prefs_org_source
    ON alert_notification_preferences(organization_id, alert_source);

CREATE INDEX IF NOT EXISTS idx_alert_notification_prefs_user_org
    ON alert_notification_preferences(user_id, organization_id);

WITH member_defaults AS (
    SELECT
        m.user_id,
        m.organization_id,
        COALESCE(np.issue_alerts AND np.error_alerts, true) AS error_email_enabled
    FROM memberships m
    LEFT JOIN LATERAL (
        SELECT n.issue_alerts, n.error_alerts
        FROM notification_preferences n
        WHERE n.user_id = m.user_id
          AND n.project_id IS NULL
        ORDER BY n.id DESC
        LIMIT 1
    ) np ON true
)
INSERT INTO alert_notification_preferences (
    user_id,
    organization_id,
    alert_source,
    email_enabled,
    slack_enabled,
    discord_enabled,
    created_at,
    updated_at
)
SELECT
    md.user_id,
    md.organization_id,
    s.alert_source,
    CASE
        WHEN s.alert_source = 'ERROR_ALERT' THEN md.error_email_enabled
        ELSE true
    END AS email_enabled,
    true AS slack_enabled,
    true AS discord_enabled,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM member_defaults md
CROSS JOIN (
    VALUES
        ('SYSTEM_ALERT'),
        ('SYSTEM_DOWN'),
        ('UPTIME_MONITOR'),
        ('ERROR_ALERT')
) AS s(alert_source)
ON CONFLICT (user_id, organization_id, alert_source) DO NOTHING;
