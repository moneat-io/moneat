-- V33: Internal incident management, timeline, and device tokens

-- Internal incidents (for native on-call escalation)
CREATE TABLE incidents (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    escalation_policy_id INTEGER REFERENCES escalation_policies(id) ON DELETE SET NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    priority_level VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    alert_source VARCHAR(100),
    deduplication_key VARCHAR(255),
    current_step INTEGER DEFAULT 0,
    repeat_iteration INTEGER DEFAULT 0,
    triggered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMP,
    acknowledged_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMP,
    resolved_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_status CHECK (status IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT check_priority CHECK (priority_level IN ('P0', 'P1', 'P2', 'P3', 'P4', 'P5'))
);

CREATE INDEX idx_incidents_org ON incidents(organization_id);
CREATE INDEX idx_incidents_status ON incidents(status);
CREATE INDEX idx_incidents_priority ON incidents(priority_level);
CREATE INDEX idx_incidents_triggered ON incidents(triggered_at);
CREATE INDEX idx_incidents_dedup ON incidents(organization_id, deduplication_key) WHERE deduplication_key IS NOT NULL;
CREATE INDEX idx_incidents_escalation_policy ON incidents(escalation_policy_id);

-- Incident timeline (audit log for incident lifecycle)
CREATE TABLE incident_timeline (
    id SERIAL PRIMARY KEY,
    incident_id INTEGER NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    event_type VARCHAR(30) NOT NULL,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    details JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_event_type CHECK (event_type IN (
        'TRIGGERED',
        'ESCALATED',
        'ACKNOWLEDGED',
        'RESOLVED',
        'REASSIGNED',
        'NOTE_ADDED',
        'STEP_TIMEOUT',
        'NOTIFICATION_SENT'
    ))
);

CREATE INDEX idx_incident_timeline_incident ON incident_timeline(incident_id, created_at);
CREATE INDEX idx_incident_timeline_actor ON incident_timeline(actor_user_id);

-- User device tokens for push notifications
CREATE TABLE user_device_tokens (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_token VARCHAR(500) NOT NULL UNIQUE,
    platform VARCHAR(20) NOT NULL,
    device_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_platform CHECK (platform IN ('IOS', 'ANDROID', 'WEB'))
);

CREATE INDEX idx_user_device_tokens_user ON user_device_tokens(user_id);
CREATE INDEX idx_user_device_tokens_token ON user_device_tokens(device_token);

-- Slack user mappings for on-call DMs
CREATE TABLE slack_user_mappings (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    slack_user_id VARCHAR(100) NOT NULL,
    slack_team_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(slack_team_id, slack_user_id)
);

CREATE INDEX idx_slack_user_mappings_user ON slack_user_mappings(user_id);
CREATE INDEX idx_slack_user_mappings_slack ON slack_user_mappings(slack_team_id, slack_user_id);
