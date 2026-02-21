-- V66: Per-alert-source routing for escalation policies

CREATE TABLE escalation_policy_alert_sources (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    alert_source VARCHAR(50) NOT NULL,
    escalation_policy_id INTEGER NOT NULL REFERENCES escalation_policies(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(organization_id, alert_source),
    CONSTRAINT check_alert_source CHECK (alert_source IN ('SYSTEM_ALERT', 'SYSTEM_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT'))
);

CREATE INDEX idx_escalation_policy_alert_sources_org_source ON escalation_policy_alert_sources(organization_id, alert_source);
