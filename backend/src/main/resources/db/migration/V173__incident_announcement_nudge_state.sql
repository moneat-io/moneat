-- Durable Slack incident nudge dismissal, activity, and rate-limit state.
CREATE TABLE native_incident_announcement_nudges (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    rule_key VARCHAR(160) NOT NULL,
    team_id VARCHAR(255) NOT NULL,
    channel_id VARCHAR(255) NOT NULL,
    nudge_key VARCHAR(64) NOT NULL,
    dismissed_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    dismissed_at TIMESTAMP,
    last_shown_at TIMESTAMP,
    last_shown_version INTEGER,
    last_activity_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_announcement_nudge_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_native_incident_announcement_nudge_key
        UNIQUE (organization_id, incident_id, rule_key, team_id, channel_id, nudge_key)
);

CREATE INDEX idx_native_incident_announcement_nudge_incident
    ON native_incident_announcement_nudges(organization_id, incident_id, updated_at);
