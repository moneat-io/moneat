-- Host-based alert tables (replacing system-based for Moneat Agent)
CREATE TABLE host_alerts (
    id SERIAL PRIMARY KEY,
    host_id INTEGER NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    metric VARCHAR(50) NOT NULL,
    condition VARCHAR(20) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    duration_seconds INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    last_triggered_at TIMESTAMPTZ,
    incident_severity VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE host_alert_settings (
    host_id INTEGER NOT NULL PRIMARY KEY REFERENCES hosts(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    scope VARCHAR(20) DEFAULT 'host',
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE host_alert_template_states (
    template_alert_id INTEGER NOT NULL REFERENCES organization_alert_templates(id) ON DELETE CASCADE,
    host_id INTEGER NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
    last_triggered_at TIMESTAMPTZ,
    PRIMARY KEY (template_alert_id, host_id)
);

CREATE INDEX idx_host_alerts_host ON host_alerts(host_id);
CREATE INDEX idx_host_alerts_org ON host_alerts(organization_id);
