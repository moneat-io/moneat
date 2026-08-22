-- Source links and an evidence-preserving canonical incident timeline.

CREATE TABLE native_incident_source_links (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    source_type VARCHAR(32) NOT NULL,
    source_key VARCHAR(500) NOT NULL,
    on_call_alert_id INTEGER REFERENCES on_call_alerts(id) ON DELETE CASCADE,
    alert_episode_id INTEGER REFERENCES alert_episodes(id) ON DELETE CASCADE,
    label VARCHAR(500),
    source_url TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    linked_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (incident_id, source_type, source_key),
    CONSTRAINT chk_native_incident_source_type CHECK (
        source_type IN ('ON_CALL_ALERT', 'ALERT_EPISODE', 'SLACK_MESSAGE', 'SOURCE_MESSAGE', 'URL')
    ),
    CONSTRAINT chk_native_incident_source_pointer CHECK (
        (source_type = 'ON_CALL_ALERT' AND on_call_alert_id IS NOT NULL AND alert_episode_id IS NULL) OR
        (source_type = 'ALERT_EPISODE' AND alert_episode_id IS NOT NULL AND on_call_alert_id IS NULL) OR
        (source_type NOT IN ('ON_CALL_ALERT', 'ALERT_EPISODE') AND
            on_call_alert_id IS NULL AND alert_episode_id IS NULL)
    )
);

CREATE INDEX idx_native_incident_source_links_incident
    ON native_incident_source_links(incident_id, created_at);

INSERT INTO native_incident_source_links (
    organization_id,
    incident_id,
    source_type,
    source_key,
    on_call_alert_id,
    label,
    linked_by,
    created_at
)
SELECT incidents.organization_id,
       links.incident_id,
       'ON_CALL_ALERT',
       alerts.resource_id::text,
       links.alert_id,
       alerts.title,
       incidents.declared_by,
       incidents.declared_at
FROM on_call_incident_alerts links
JOIN on_call_incidents incidents ON incidents.id = links.incident_id
JOIN on_call_alerts alerts ON alerts.id = links.alert_id
ON CONFLICT (incident_id, source_type, source_key) DO NOTHING;

INSERT INTO native_incident_source_links (
    organization_id,
    incident_id,
    source_type,
    source_key,
    alert_episode_id,
    label,
    linked_by,
    created_at
)
SELECT links.organization_id,
       links.incident_id,
       'ALERT_EPISODE',
       episodes.resource_id::text,
       links.alert_episode_id,
       episodes.title,
       incidents.declared_by,
       links.created_at
FROM native_incident_alert_episode_links links
JOIN on_call_incidents incidents ON incidents.id = links.incident_id
JOIN alert_episodes episodes ON episodes.id = links.alert_episode_id
ON CONFLICT (incident_id, source_type, source_key) DO NOTHING;

ALTER TABLE on_call_incident_timeline
    ADD COLUMN organization_id INTEGER REFERENCES organizations(id) ON DELETE CASCADE,
    ADD COLUMN event_key VARCHAR(200),
    ADD COLUMN source_type VARCHAR(48),
    ADD COLUMN source_reference VARCHAR(500),
    ADD COLUMN source_url TEXT,
    ADD COLUMN provenance VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN visibility VARCHAR(24) NOT NULL DEFAULT 'ORGANIZATION',
    ADD COLUMN original_occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN observed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN display_order BIGINT,
    ADD COLUMN annotation TEXT,
    ADD COLUMN edited_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN edited_by INTEGER REFERENCES users(id),
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN deleted_by INTEGER REFERENCES users(id);

UPDATE on_call_incident_timeline timeline
SET organization_id = incidents.organization_id,
    event_key = 'legacy:' || timeline.resource_id::text,
    original_occurred_at = timeline.created_at,
    observed_at = timeline.created_at,
    display_order = (EXTRACT(EPOCH FROM timeline.created_at) * 1000000)::BIGINT + timeline.id
FROM on_call_incidents incidents
WHERE incidents.id = timeline.incident_id;

UPDATE on_call_incident_timeline
SET details = '{}'::jsonb
WHERE details IS NULL;

ALTER TABLE on_call_incident_timeline
    ALTER COLUMN organization_id SET NOT NULL,
    ALTER COLUMN event_key SET NOT NULL,
    ALTER COLUMN original_occurred_at SET NOT NULL,
    ALTER COLUMN display_order SET NOT NULL,
    ALTER COLUMN details SET DEFAULT '{}'::jsonb,
    ALTER COLUMN details SET NOT NULL,
    ALTER COLUMN event_type TYPE VARCHAR(80);

ALTER TABLE on_call_incident_timeline
    ADD CONSTRAINT uq_native_incident_timeline_event_key UNIQUE (incident_id, event_key),
    ADD CONSTRAINT chk_native_incident_timeline_provenance CHECK (
        provenance IN ('INTERNAL', 'REST', 'SLACK', 'INTEGRATION', 'IMPORT', 'WORKFLOW')
    ),
    ADD CONSTRAINT chk_native_incident_timeline_visibility CHECK (
        visibility IN ('ORGANIZATION', 'PARTICIPANTS', 'PRIVATE', 'PUBLIC')
    ),
    ADD CONSTRAINT chk_native_incident_timeline_edit CHECK (
        (edited_at IS NULL AND edited_by IS NULL) OR (edited_at IS NOT NULL AND edited_by IS NOT NULL)
    ),
    ADD CONSTRAINT chk_native_incident_timeline_delete CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    );

CREATE INDEX idx_native_incident_timeline_order
    ON on_call_incident_timeline(incident_id, display_order, id);
CREATE INDEX idx_native_incident_timeline_filter
    ON on_call_incident_timeline(incident_id, event_type, visibility, deleted_at);

INSERT INTO on_call_incident_timeline (
    organization_id,
    incident_id,
    event_key,
    event_type,
    actor_user_id,
    details,
    source_type,
    source_reference,
    provenance,
    visibility,
    original_occurred_at,
    observed_at,
    display_order,
    created_at
)
SELECT incidents.organization_id,
       links.incident_id,
       'alert:' || alerts.resource_id::text || ':' || timeline.resource_id::text,
       timeline.event_type,
       timeline.actor_user_id,
       COALESCE(timeline.details, '{}'::jsonb),
       'ON_CALL_ALERT',
       alerts.resource_id::text,
       'INTEGRATION',
       'ORGANIZATION',
       timeline.created_at,
       timeline.created_at,
       (EXTRACT(EPOCH FROM timeline.created_at) * 1000000)::BIGINT + timeline.id,
       timeline.created_at
FROM on_call_incident_alerts links
JOIN on_call_incidents incidents ON incidents.id = links.incident_id
JOIN on_call_alerts alerts ON alerts.id = links.alert_id
JOIN on_call_alert_timeline timeline ON timeline.alert_id = links.alert_id
ON CONFLICT (incident_id, event_key) DO NOTHING;

CREATE TABLE native_incident_timeline_revisions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    timeline_event_id INTEGER NOT NULL REFERENCES on_call_incident_timeline(id) ON DELETE CASCADE,
    revision_number INTEGER NOT NULL,
    action VARCHAR(24) NOT NULL,
    previous_snapshot JSONB NOT NULL,
    next_snapshot JSONB NOT NULL,
    reason TEXT,
    edited_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (timeline_event_id, revision_number),
    CONSTRAINT chk_native_incident_timeline_revision CHECK (revision_number > 0),
    CONSTRAINT chk_native_incident_timeline_revision_action CHECK (
        action IN ('EDIT', 'ANNOTATE', 'REORDER', 'DELETE', 'RESTORE')
    )
);

CREATE INDEX idx_native_incident_timeline_revisions_event
    ON native_incident_timeline_revisions(timeline_event_id, revision_number);
