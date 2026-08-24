-- Versioned, bounded escalation control-flow snapshots and durable execution history.
CREATE TABLE escalation_policy_versions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    escalation_policy_id INTEGER NOT NULL REFERENCES escalation_policies(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    path JSONB NOT NULL,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT uq_escalation_policy_versions_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_escalation_policy_versions_number UNIQUE (escalation_policy_id, version),
    CONSTRAINT chk_escalation_policy_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_escalation_policy_version_number CHECK (version > 0)
);

CREATE INDEX idx_escalation_policy_versions_policy
    ON escalation_policy_versions(organization_id, escalation_policy_id, version DESC);

CREATE UNIQUE INDEX uq_escalation_policy_versions_published
    ON escalation_policy_versions(escalation_policy_id)
    WHERE status = 'PUBLISHED';

ALTER TABLE on_call_alerts
    ADD COLUMN IF NOT EXISTS escalation_policy_version_id INTEGER
        REFERENCES escalation_policy_versions(id) ON DELETE SET NULL;

CREATE TABLE escalation_execution_states (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    alert_id INTEGER NOT NULL REFERENCES on_call_alerts(id) ON DELETE CASCADE,
    policy_version_id INTEGER NOT NULL REFERENCES escalation_policy_versions(id) ON DELETE RESTRICT,
    current_node_id VARCHAR(128),
    transition_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_escalation_execution_state_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_escalation_execution_state_alert UNIQUE (organization_id, alert_id),
    CONSTRAINT chk_escalation_execution_transition_count CHECK (transition_count >= 0)
);

CREATE TABLE escalation_execution_events (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    execution_id INTEGER NOT NULL REFERENCES escalation_execution_states(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    node_id VARCHAR(128),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_escalation_execution_event_resource UNIQUE (organization_id, resource_id)
);

CREATE INDEX idx_escalation_execution_events_execution
    ON escalation_execution_events(organization_id, execution_id, created_at);
