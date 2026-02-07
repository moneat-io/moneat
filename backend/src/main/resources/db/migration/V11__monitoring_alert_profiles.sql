-- Shared/global monitoring alert profiles + per-system scope

CREATE TABLE organization_alert_templates (
    id SERIAL PRIMARY KEY,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    metric VARCHAR(50) NOT NULL,
    condition VARCHAR(20) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    duration_seconds INT DEFAULT 0,
    enabled BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_org_alert_templates_org ON organization_alert_templates(organization_id);
CREATE INDEX idx_org_alert_templates_enabled ON organization_alert_templates(enabled);

CREATE TABLE system_alert_settings (
    system_id UUID PRIMARY KEY REFERENCES systems(id) ON DELETE CASCADE,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    scope VARCHAR(20) NOT NULL DEFAULT 'system',
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    CHECK (scope IN ('global', 'system'))
);

CREATE INDEX idx_system_alert_settings_org ON system_alert_settings(organization_id);
CREATE INDEX idx_system_alert_settings_scope ON system_alert_settings(scope);

CREATE TABLE system_alert_template_states (
    template_alert_id INT NOT NULL REFERENCES organization_alert_templates(id) ON DELETE CASCADE,
    system_id UUID NOT NULL REFERENCES systems(id) ON DELETE CASCADE,
    last_triggered_at TIMESTAMPTZ,
    PRIMARY KEY (template_alert_id, system_id)
);

CREATE INDEX idx_template_states_system ON system_alert_template_states(system_id);

-- Keep existing behavior for existing systems: system-specific scope.
INSERT INTO system_alert_settings (system_id, organization_id, scope, updated_at)
SELECT s.id, s.organization_id, 'system', NOW()
FROM systems s
LEFT JOIN system_alert_settings sas ON sas.system_id = s.id
WHERE sas.system_id IS NULL;
