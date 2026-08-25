-- Versioned incident announcement rules and durable Slack card state.
CREATE TABLE native_incident_announcement_rules (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    version INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    team_id VARCHAR(255),
    channel_id VARCHAR(255),
    announce_triage BOOLEAN NOT NULL DEFAULT FALSE,
    allow_private BOOLEAN NOT NULL DEFAULT FALSE,
    allow_test BOOLEAN NOT NULL DEFAULT FALSE,
    conditions JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_announcement_rule_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_native_incident_announcement_rule_version UNIQUE (organization_id, name, version)
);

CREATE INDEX idx_native_incident_announcement_rule_enabled
    ON native_incident_announcement_rules(organization_id, enabled);

CREATE TABLE native_incident_announcements (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    rule_key VARCHAR(160) NOT NULL,
    rule_version INTEGER NOT NULL,
    team_id VARCHAR(255) NOT NULL,
    channel_id VARCHAR(255) NOT NULL,
    desired_version INTEGER NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    delivery_resource_id UUID,
    provider_message_ts VARCHAR(64),
    thread_message_ts VARCHAR(64),
    card_payload TEXT NOT NULL,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_announcement_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_native_incident_announcement_destination
        UNIQUE (organization_id, incident_id, rule_key, team_id, channel_id),
    CONSTRAINT chk_native_incident_announcement_state
        CHECK (state IN ('PENDING', 'ACTIVE', 'FAILED', 'ARCHIVED'))
);

CREATE INDEX idx_native_incident_announcement_incident
    ON native_incident_announcements(organization_id, incident_id, desired_version);
