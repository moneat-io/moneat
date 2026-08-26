-- Durable incident actions and append-only action audit history.
CREATE TABLE native_incident_actions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    assignee_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    state VARCHAR(24) NOT NULL,
    source VARCHAR(32) NOT NULL,
    slack_channel_id VARCHAR(128),
    slack_message_ts VARCHAR(64),
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    claimed_at TIMESTAMP,
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    converted_to_follow_up_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_action_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_native_incident_action_state
        CHECK (state IN ('OPEN', 'CLAIMED', 'COMPLETED', 'CANCELLED', 'FOLLOW_UP')),
    CONSTRAINT chk_native_incident_action_source
        CHECK (source IN ('COMMAND', 'MODAL', 'REACTION', 'MESSAGE_SHORTCUT', 'DASHBOARD', 'API', 'WORKFLOW', 'AI_PROPOSAL', 'SLACK'))
);

CREATE INDEX idx_native_incident_action_incident_state
    ON native_incident_actions(incident_id, state, created_at);

CREATE TABLE native_incident_action_events (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    action_id INTEGER NOT NULL REFERENCES native_incident_actions(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    event_type VARCHAR(48) NOT NULL,
    from_state VARCHAR(24),
    to_state VARCHAR(24),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_action_event_resource UNIQUE (organization_id, resource_id)
);

CREATE INDEX idx_native_incident_action_event_action
    ON native_incident_action_events(action_id, created_at);
CREATE INDEX idx_native_incident_action_event_incident
    ON native_incident_action_events(incident_id, created_at);
