CREATE TABLE alert_episodes (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source VARCHAR(64) NOT NULL,
    deduplication_key TEXT NOT NULL,
    episode_seq INTEGER NOT NULL,
    episode_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    last_notification_at TIMESTAMPTZ,
    notification_count INTEGER NOT NULL DEFAULT 0,
    suppressed_at TIMESTAMPTZ,
    suppressed_by_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    suppress_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_alert_episodes_open_dedup
    ON alert_episodes (organization_id, source, deduplication_key)
    WHERE status = 'FIRING';

CREATE UNIQUE INDEX idx_alert_episodes_sequence
    ON alert_episodes (organization_id, source, deduplication_key, episode_seq);

CREATE UNIQUE INDEX idx_alert_episodes_key
    ON alert_episodes (organization_id, episode_key);

CREATE INDEX idx_alert_episodes_recent_history
    ON alert_episodes (organization_id, source, deduplication_key, opened_at DESC);

CREATE INDEX idx_alert_episodes_org_status
    ON alert_episodes (organization_id, status, last_seen_at DESC);

UPDATE workflow_versions wv
SET once_for_template = '["alert.episode_key", "alert.notification_sequence"]'::JSONB
FROM workflows w
WHERE w.id = wv.workflow_id
    AND wv.most_recent = TRUE
    AND w.trigger_name IN ('alert.triggered', 'monitor.alerted', 'uptime.down', 'synthetic.failed')
    AND wv.once_for_template = '["alert.deduplication_key"]'::JSONB;

UPDATE workflow_versions wv
SET once_for_template = '["alert.episode_key", "alert.status"]'::JSONB
FROM workflows w
WHERE w.id = wv.workflow_id
    AND wv.most_recent = TRUE
    AND w.trigger_name IN ('alert.resolved', 'monitor.recovered', 'uptime.up', 'synthetic.passed')
    AND wv.once_for_template = '["alert.deduplication_key", "alert.status"]'::JSONB;

UPDATE workflow_versions wv
SET once_for_template = '["alert.episode_key"]'::JSONB
FROM workflows w
WHERE w.id = wv.workflow_id
    AND wv.most_recent = TRUE
    AND w.trigger_name = 'incident.created'
    AND wv.once_for_template = '["alert.deduplication_key"]'::JSONB;

UPDATE workflow_versions wv
SET once_for_template = '["alert.episode_key", "incident.status"]'::JSONB
FROM workflows w
WHERE w.id = wv.workflow_id
    AND wv.most_recent = TRUE
    AND w.trigger_name = 'incident.resolved'
    AND wv.once_for_template = '["alert.deduplication_key", "incident.status"]'::JSONB;

UPDATE workflows
SET updated_at = NOW()
WHERE trigger_name IN (
    'alert.triggered',
    'alert.resolved',
    'monitor.alerted',
    'monitor.recovered',
    'uptime.down',
    'uptime.up',
    'synthetic.failed',
    'synthetic.passed',
    'incident.created',
    'incident.resolved'
);
