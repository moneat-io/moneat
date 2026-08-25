-- Durable structured incident updates and requested-update reminder state.
ALTER TABLE on_call_incidents
    ADD COLUMN customer_impact VARCHAR(64),
    ADD COLUMN next_update_at TIMESTAMP,
    ADD COLUMN update_reminder_paused BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_update_at TIMESTAMP;

CREATE TABLE native_incident_update_requests (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    requested_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    message TEXT,
    due_at TIMESTAMP NOT NULL,
    status VARCHAR(24) NOT NULL,
    escalation_level INTEGER NOT NULL DEFAULT 0,
    last_reminded_at TIMESTAMP,
    fulfilled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_update_request_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_update_request_status
        CHECK (status IN ('OPEN', 'FULFILLED', 'PAUSED', 'CANCELLED')),
    CONSTRAINT chk_native_incident_update_request_escalation_level
        CHECK (escalation_level >= 0)
);

CREATE INDEX idx_native_incident_update_request_due
    ON native_incident_update_requests(organization_id, status, due_at);
CREATE INDEX idx_native_incident_update_request_incident
    ON native_incident_update_requests(incident_id, status);
