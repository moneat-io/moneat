ALTER TABLE events ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE events ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE events ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE issues ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE issues ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE issues ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

DROP TABLE IF EXISTS issues_mv;

CREATE MATERIALIZED VIEW IF NOT EXISTS issues_mv TO issues AS
SELECT
    issue_id,
    project_id,
    organization_id,
    fingerprint,
    min(timestamp) as first_seen,
    max(timestamp) as last_seen,
    count() as event_count,
    uniq(user_id) as user_count,
    any(message) as title,
    any(exception_type) as culprit,
    any(level) as level,
    any(platform) as platform,
    CAST('unresolved' AS Enum8('unresolved' = 1, 'resolved' = 2, 'ignored' = 3)) as status,
    max(timestamp) as updated_at
FROM events
WHERE event_type = 'error'
GROUP BY issue_id, project_id, organization_id, fingerprint;

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE spans ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE spans ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE spans ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE replay_events ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE replay_events ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE replay_events ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE replay_segments ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE replay_segments ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE replay_segments ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE user_feedback ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE user_feedback ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE user_feedback ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE llm_generations ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE llm_generations ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE llm_generations ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE event_project_rollup_1h ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE event_project_rollup_1h ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE event_project_rollup_1h ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;

ALTER TABLE event_issue_rollup_1h ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0 AFTER project_id;
ALTER TABLE event_issue_rollup_1h ADD COLUMN IF NOT EXISTS org_id UInt64 ALIAS organization_id AFTER organization_id;
ALTER TABLE event_issue_rollup_1h ADD COLUMN IF NOT EXISTS service_id UInt64 ALIAS project_id AFTER org_id;
