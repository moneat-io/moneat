-- Durable post-incident follow-ups and policy tracking.
ALTER TABLE native_incident_commands
    ADD COLUMN follow_up_resource_id UUID;

CREATE TABLE native_incident_follow_ups (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    owner_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    owner_team_id INTEGER REFERENCES organization_teams(id) ON DELETE SET NULL,
    priority VARCHAR(8) NOT NULL,
    labels_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    due_at TIMESTAMP,
    sla_minutes INTEGER,
    reminder_minutes INTEGER,
    next_reminder_at TIMESTAMP,
    escalation_level INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    accepted_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    accepted_at TIMESTAMP,
    completed_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    completed_at TIMESTAMP,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    source VARCHAR(32) NOT NULL,
    slack_channel_id VARCHAR(128),
    slack_message_ts VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_follow_up_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_follow_up_priority CHECK (priority IN ('P0', 'P1', 'P2', 'P3', 'P4', 'P5')),
    CONSTRAINT chk_native_incident_follow_up_status CHECK (
        status IN ('OPEN', 'ACCEPTED', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT chk_native_incident_follow_up_sla CHECK (sla_minutes IS NULL OR sla_minutes > 0),
    CONSTRAINT chk_native_incident_follow_up_reminder CHECK (reminder_minutes IS NULL OR reminder_minutes > 0),
    CONSTRAINT chk_native_incident_follow_up_escalation CHECK (escalation_level >= 0)
);

CREATE INDEX idx_native_incident_follow_up_queue
    ON native_incident_follow_ups(organization_id, status, priority, due_at);
CREATE INDEX idx_native_incident_follow_up_incident
    ON native_incident_follow_ups(incident_id, status);
