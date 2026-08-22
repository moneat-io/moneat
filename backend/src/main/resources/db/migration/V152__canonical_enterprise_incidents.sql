-- Expand the existing declared-incident record into the canonical native incident aggregate.
-- The AGPL incident-provider tables remain independent and are intentionally untouched.

UPDATE on_call_incidents
SET status = 'ACTIVE'
WHERE status = 'OPEN';

ALTER TABLE on_call_incidents
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

ALTER TABLE on_call_incidents
    ADD COLUMN mode VARCHAR(24) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN visibility VARCHAR(24) NOT NULL DEFAULT 'ORGANIZATION',
    ADD COLUMN incident_type VARCHAR(100),
    ADD COLUMN summary TEXT,
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN triaged_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN post_incident_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN declined_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE on_call_incidents
    ADD CONSTRAINT chk_native_incident_status CHECK (
        status IN ('TRIAGE', 'ACTIVE', 'RESOLVED', 'POST_INCIDENT', 'CLOSED', 'CANCELLED', 'DECLINED')
    ),
    ADD CONSTRAINT chk_native_incident_mode CHECK (
        mode IN ('LIVE', 'RETROSPECTIVE', 'TEST')
    ),
    ADD CONSTRAINT chk_native_incident_visibility CHECK (
        visibility IN ('ORGANIZATION', 'PRIVATE', 'PUBLIC')
    ),
    ADD CONSTRAINT chk_native_incident_version CHECK (version > 0);

CREATE INDEX idx_on_call_incidents_org_status_updated
    ON on_call_incidents(organization_id, status, updated_at DESC);

-- The original timeline table restricted event_type to four legacy values.
-- Native incident commands now persist lifecycle, assignment, action, merge,
-- and organization-defined timeline event types in this same audit stream.
ALTER TABLE on_call_incident_timeline
    DROP CONSTRAINT IF EXISTS on_call_incident_timeline_event_type_check;

ALTER TABLE on_call_incident_alerts
    ADD COLUMN status_owner VARCHAR(24) NOT NULL DEFAULT 'INCIDENT',
    ADD COLUMN severity_owner VARCHAR(24) NOT NULL DEFAULT 'INCIDENT',
    ADD COLUMN resolution_owner VARCHAR(24) NOT NULL DEFAULT 'INCIDENT';

ALTER TABLE on_call_incident_alerts
    ADD CONSTRAINT chk_incident_alert_status_owner CHECK (status_owner IN ('INCIDENT', 'ALERT')),
    ADD CONSTRAINT chk_incident_alert_severity_owner CHECK (severity_owner IN ('INCIDENT', 'ALERT')),
    ADD CONSTRAINT chk_incident_alert_resolution_owner CHECK (resolution_owner IN ('INCIDENT', 'ALERT'));

-- V41 already guarantees one incident link per alert. Realign the denormalized
-- alert pointer with that canonical link before new commands begin using it.
UPDATE on_call_alerts alerts
SET declared_incident_id = links.incident_id
FROM on_call_incident_alerts links
WHERE alerts.id = links.alert_id
  AND alerts.declared_incident_id IS DISTINCT FROM links.incident_id;

CREATE TABLE native_incident_alert_episode_links (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    alert_episode_id INTEGER NOT NULL REFERENCES alert_episodes(id) ON DELETE CASCADE,
    status_owner VARCHAR(24) NOT NULL DEFAULT 'INCIDENT',
    severity_owner VARCHAR(24) NOT NULL DEFAULT 'INCIDENT',
    resolution_owner VARCHAR(24) NOT NULL DEFAULT 'INCIDENT',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (incident_id, alert_episode_id),
    UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_incident_episode_status_owner CHECK (status_owner IN ('INCIDENT', 'ALERT_EPISODE')),
    CONSTRAINT chk_incident_episode_severity_owner CHECK (severity_owner IN ('INCIDENT', 'ALERT_EPISODE')),
    CONSTRAINT chk_incident_episode_resolution_owner CHECK (resolution_owner IN ('INCIDENT', 'ALERT_EPISODE'))
);

CREATE INDEX idx_native_incident_episode_links_episode
    ON native_incident_alert_episode_links(alert_episode_id);

CREATE TABLE native_incident_commands (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER REFERENCES on_call_incidents(id) ON DELETE SET NULL,
    actor_user_id INTEGER NOT NULL REFERENCES users(id),
    command_key VARCHAR(160) NOT NULL,
    command_type VARCHAR(48) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expected_version INTEGER,
    result_version INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_native_incident_commands_org_command_key UNIQUE (organization_id, command_key),
    UNIQUE (organization_id, resource_id)
);

CREATE INDEX idx_native_incident_commands_incident_created
    ON native_incident_commands(incident_id, created_at DESC);
