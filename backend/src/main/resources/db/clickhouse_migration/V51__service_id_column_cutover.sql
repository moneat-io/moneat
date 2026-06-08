DROP TABLE IF EXISTS issues_mv;
DROP TABLE IF EXISTS analytics_sessions_hourly_mv;

ALTER TABLE events DROP COLUMN IF EXISTS service_id;
ALTER TABLE events ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE issues DROP COLUMN IF EXISTS service_id;
ALTER TABLE issues ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

CREATE MATERIALIZED VIEW IF NOT EXISTS issues_mv TO issues AS
SELECT
    issue_id,
    project_id,
    service_id,
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
GROUP BY issue_id, project_id, service_id, organization_id, fingerprint;

ALTER TABLE sessions DROP COLUMN IF EXISTS service_id;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE spans DROP COLUMN IF EXISTS service_id;
ALTER TABLE spans ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE replay_events DROP COLUMN IF EXISTS service_id;
ALTER TABLE replay_events ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE replay_segments DROP COLUMN IF EXISTS service_id;
ALTER TABLE replay_segments ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE user_feedback DROP COLUMN IF EXISTS service_id;
ALTER TABLE user_feedback ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE llm_generations DROP COLUMN IF EXISTS service_id;
ALTER TABLE llm_generations ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE event_project_rollup_1h DROP COLUMN IF EXISTS service_id;
ALTER TABLE event_project_rollup_1h ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE event_issue_rollup_1h DROP COLUMN IF EXISTS service_id;
ALTER TABLE event_issue_rollup_1h ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE analytics_events ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE analytics_sessions_hourly ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

CREATE MATERIALIZED VIEW IF NOT EXISTS analytics_sessions_hourly_mv
TO analytics_sessions_hourly AS
SELECT
    project_id,
    service_id,
    session_id,
    toStartOfHour(min(timestamp)) AS hour,
    min(timestamp) AS started,
    max(timestamp) AS ended,
    countIf(event_name = 'pageview') AS pageviews,
    count() AS events,
    argMin(pathname, timestamp) AS entry_page,
    argMax(pathname, timestamp) AS exit_page,
    argMin(referrer_source, timestamp) AS referrer_source,
    argMin(utm_source, timestamp) AS utm_source,
    argMin(utm_medium, timestamp) AS utm_medium,
    argMin(utm_campaign, timestamp) AS utm_campaign,
    argMin(country_code, timestamp) AS country_code,
    argMin(browser, timestamp) AS browser,
    argMin(os, timestamp) AS os,
    argMin(device_type, timestamp) AS device_type,
    if(countIf(event_name = 'pageview') = 1, 1, 0) AS is_bounce
FROM analytics_events
GROUP BY project_id, service_id, session_id;

ALTER TABLE logs ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE metrics ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;

ALTER TABLE metric_sketches ADD COLUMN IF NOT EXISTS service_id UInt64 DEFAULT project_id AFTER project_id;
