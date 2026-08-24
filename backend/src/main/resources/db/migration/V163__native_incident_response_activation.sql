-- Durable incident-response activation policy, execution, and delivery state.

CREATE TABLE native_incident_response_policies (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    commander_policy_id INTEGER REFERENCES escalation_policies(id) ON DELETE SET NULL,
    ownership_policy_id INTEGER REFERENCES escalation_policies(id) ON DELETE SET NULL,
    page_ownership BOOLEAN NOT NULL DEFAULT TRUE,
    page_test_incidents BOOLEAN NOT NULL DEFAULT FALSE,
    page_retrospective_incidents BOOLEAN NOT NULL DEFAULT FALSE,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_response_policies_org UNIQUE (organization_id),
    CONSTRAINT uq_native_incident_response_policies_resource UNIQUE (organization_id, resource_id)
);

CREATE TABLE native_incident_response_activations (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    activation_revision INTEGER NOT NULL,
    trigger VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    desired_count INTEGER NOT NULL DEFAULT 0,
    attempted_count INTEGER NOT NULL DEFAULT 0,
    acknowledged_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_response_activation_key
        UNIQUE (organization_id, incident_id, activation_revision, trigger),
    CONSTRAINT uq_native_incident_response_activation_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_response_activation_counts
        CHECK (desired_count >= 0 AND attempted_count >= 0 AND acknowledged_count >= 0)
);

CREATE INDEX idx_native_incident_response_activations_incident
    ON native_incident_response_activations(organization_id, incident_id, created_at);

CREATE TABLE native_incident_response_targets (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    activation_id INTEGER NOT NULL REFERENCES native_incident_response_activations(id) ON DELETE CASCADE,
    target_key VARCHAR(180) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    escalation_policy_id INTEGER REFERENCES escalation_policies(id) ON DELETE SET NULL,
    user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    on_call_alert_id INTEGER REFERENCES on_call_alerts(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    desired_at TIMESTAMP NOT NULL,
    attempted_at TIMESTAMP,
    acknowledged_at TIMESTAMP,
    failed_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_response_target_key
        UNIQUE (organization_id, activation_id, target_key),
    CONSTRAINT uq_native_incident_response_target_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_response_target_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_native_incident_response_targets_alert
    ON native_incident_response_targets(organization_id, on_call_alert_id);
