-- Hot-path telemetry rollups populated from live ingestion. These tables are
-- intentionally not backfilled by this migration.
CREATE TABLE IF NOT EXISTS metrics_latest_by_host (
    organization_id UInt64,
    host_id UInt64,
    metric_name LowCardinality(String),
    timestamp DateTime64(3, 'UTC'),
    value Float64,
    host String DEFAULT '',
    unit String DEFAULT '',
    source LowCardinality(String) DEFAULT '',
    tags Map(String, String)
) ENGINE = ReplacingMergeTree(timestamp)
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, host_id, metric_name)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS metrics_rollup_1m (
    organization_id UInt64,
    host_id UInt64,
    bucket_start DateTime64(3, 'UTC'),
    metric_name LowCardinality(String),
    host String DEFAULT '',
    unit String DEFAULT '',
    source LowCardinality(String) DEFAULT '',
    value_sum Float64,
    value_count UInt64
) ENGINE = SummingMergeTree((value_sum, value_count))
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, host_id, bucket_start, metric_name)
TTL toDateTime(bucket_start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS containers_latest_by_host (
    organization_id UInt64,
    host_id UInt64,
    host String,
    container_id String,
    name String DEFAULT '',
    image String DEFAULT '',
    state LowCardinality(String) DEFAULT 'running',
    cpu_percent Float64 DEFAULT 0,
    mem_usage UInt64 DEFAULT 0,
    mem_limit UInt64 DEFAULT 0,
    net_rx_bytes UInt64 DEFAULT 0,
    net_tx_bytes UInt64 DEFAULT 0,
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree(timestamp)
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, host_id, host, container_id)
TTL toDateTime(timestamp) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS containers_rollup_1m (
    organization_id UInt64,
    host_id UInt64,
    bucket_start DateTime64(3, 'UTC'),
    host String,
    container_id String,
    name String DEFAULT '',
    cpu_sum Float64,
    cpu_count UInt64,
    mem_usage_sum UInt64,
    mem_limit_sum UInt64,
    net_rx_bytes_sum UInt64,
    net_tx_bytes_sum UInt64
) ENGINE = SummingMergeTree((
    cpu_sum,
    cpu_count,
    mem_usage_sum,
    mem_limit_sum,
    net_rx_bytes_sum,
    net_tx_bytes_sum
))
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, host_id, bucket_start, container_id, name)
TTL toDateTime(bucket_start) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

-- Lightweight error-event rollups for dashboard counts and timelines. These are
-- populated only by new ingestion writes after this migration lands.
CREATE TABLE IF NOT EXISTS event_project_rollup_1h (
    project_id UInt64,
    bucket_start DateTime64(3, 'UTC'),
    level LowCardinality(String),
    platform LowCardinality(String),
    browser_name LowCardinality(String),
    environment LowCardinality(String),
    event_count UInt64
) ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (project_id, bucket_start, level, platform, browser_name, environment)
TTL toDateTime(bucket_start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS event_issue_rollup_1h (
    project_id UInt64,
    issue_id String,
    bucket_start DateTime64(3, 'UTC'),
    title String,
    event_count UInt64
) ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (project_id, bucket_start, issue_id, title)
TTL toDateTime(bucket_start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
