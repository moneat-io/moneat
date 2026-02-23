-- Dashboard widget alerts: threshold-based alerting on widget queries

CREATE TABLE dashboard_widget_alerts (
    id BIGSERIAL PRIMARY KEY,
    widget_id BIGINT NOT NULL REFERENCES dashboard_widgets(id) ON DELETE CASCADE,
    dashboard_id BIGINT NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    org_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    condition VARCHAR(5) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    metric_index INT NOT NULL DEFAULT 0,
    duration_seconds INT NOT NULL DEFAULT 0,
    incident_severity VARCHAR(20),
    enabled BOOLEAN NOT NULL DEFAULT true,
    notification_channels JSONB NOT NULL DEFAULT '{"email":true,"slack":true,"discord":true}',
    last_triggered_at TIMESTAMPTZ,
    last_value DOUBLE PRECISION,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (condition IN ('>', '<', '>=', '<=', '=='))
);

CREATE INDEX idx_dwa_widget ON dashboard_widget_alerts(widget_id);
CREATE INDEX idx_dwa_dashboard ON dashboard_widget_alerts(dashboard_id);
CREATE INDEX idx_dwa_org_enabled ON dashboard_widget_alerts(org_id, enabled);

-- Add DASHBOARD_ALERT to the alert_notification_preferences CHECK constraint
ALTER TABLE alert_notification_preferences
    DROP CONSTRAINT IF EXISTS alert_notification_preferences_check,
    ADD CONSTRAINT alert_notification_preferences_check
        CHECK (alert_source IN ('SYSTEM_ALERT', 'SYSTEM_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT', 'DASHBOARD_ALERT'));

-- Add DASHBOARD_ALERT to escalation_policy_alert_sources CHECK constraint
ALTER TABLE escalation_policy_alert_sources
    DROP CONSTRAINT IF EXISTS check_alert_source,
    ADD CONSTRAINT check_alert_source
        CHECK (alert_source IN ('SYSTEM_ALERT', 'SYSTEM_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT', 'DASHBOARD_ALERT'));

-- Seed default DASHBOARD_ALERT preferences for existing users (all channels enabled)
INSERT INTO alert_notification_preferences (
    user_id, organization_id, alert_source,
    email_enabled, slack_enabled, discord_enabled,
    created_at, updated_at
)
SELECT
    m.user_id,
    m.organization_id,
    'DASHBOARD_ALERT',
    true, true, true,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM memberships m
ON CONFLICT (user_id, organization_id, alert_source) DO NOTHING;
