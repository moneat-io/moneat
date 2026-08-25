-- Moneat - observability platform
-- Copyright (C) 2026 Moneat
-- Licensed under the GNU Affero General Public License, version 3.

CREATE TABLE native_incident_slack_channels (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    team_id VARCHAR(255) NOT NULL,
    channel_id VARCHAR(255),
    channel_name VARCHAR(80),
    state VARCHAR(24) NOT NULL DEFAULT 'CHANNELLESS',
    is_private BOOLEAN NOT NULL DEFAULT FALSE,
    desired_version INTEGER NOT NULL DEFAULT 1,
    delivery_resource_id UUID,
    topic VARCHAR(2000),
    bookmarks TEXT,
    last_error TEXT,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_slack_channel_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_native_incident_slack_channel_workspace UNIQUE (organization_id, incident_id, team_id)
);

CREATE INDEX idx_native_incident_slack_channel_state
    ON native_incident_slack_channels(organization_id, incident_id, state);
