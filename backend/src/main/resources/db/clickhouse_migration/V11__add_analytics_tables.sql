-- Product analytics tables for privacy-focused web analytics

CREATE TABLE IF NOT EXISTS moneat.analytics_events (
    event_id       UUID DEFAULT generateUUIDv4(),
    project_id     UInt64,
    session_id     String,
    event_name     String,
    hostname       String,
    pathname       String,
    referrer       String DEFAULT '',
    referrer_source String DEFAULT '',
    utm_source     String DEFAULT '',
    utm_medium     String DEFAULT '',
    utm_campaign   String DEFAULT '',
    utm_term       String DEFAULT '',
    utm_content    String DEFAULT '',
    country_code   LowCardinality(String) DEFAULT '',
    subdivision    String DEFAULT '',
    city           String DEFAULT '',
    browser        LowCardinality(String) DEFAULT '',
    browser_version String DEFAULT '',
    os             LowCardinality(String) DEFAULT '',
    os_version     String DEFAULT '',
    device_type    LowCardinality(String) DEFAULT '',
    screen_width   UInt16 DEFAULT 0,
    props          Map(String, String),
    timestamp      DateTime64(3, 'UTC')
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (project_id, timestamp, session_id)
TTL timestamp + INTERVAL 730 DAY;

-- Pre-aggregated session view for fast dashboard queries
CREATE TABLE IF NOT EXISTS moneat.analytics_sessions_hourly (
    project_id     UInt64,
    session_id     String,
    hour           DateTime,
    started        DateTime64(3, 'UTC'),
    ended          DateTime64(3, 'UTC'),
    pageviews      UInt32,
    events         UInt32,
    entry_page     String,
    exit_page      String,
    referrer_source String,
    utm_source     String,
    utm_medium     String,
    utm_campaign   String,
    country_code   LowCardinality(String),
    browser        LowCardinality(String),
    os             LowCardinality(String),
    device_type    LowCardinality(String),
    is_bounce      UInt8
) ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(hour)
ORDER BY (project_id, hour, session_id);

CREATE MATERIALIZED VIEW IF NOT EXISTS moneat.analytics_sessions_hourly_mv
TO moneat.analytics_sessions_hourly AS
SELECT
    project_id,
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
FROM moneat.analytics_events
GROUP BY project_id, session_id;
