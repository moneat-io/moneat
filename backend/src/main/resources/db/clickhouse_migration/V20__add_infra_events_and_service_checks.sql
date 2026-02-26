-- Infrastructure events table
CREATE TABLE IF NOT EXISTS infra_events (
    event_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    title String,
    text String DEFAULT '',
    timestamp DateTime64(3, 'UTC'),
    priority Enum8('normal' = 1, 'low' = 2),
    host String DEFAULT '',
    tags Map(String, String),
    alert_type Enum8('info' = 1, 'warning' = 2, 'error' = 3, 'success' = 4),
    aggregation_key String DEFAULT '',
    source_type_name String DEFAULT '',
    device_name String DEFAULT '',
    INDEX idx_infra_events_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_infra_events_alert alert_type TYPE set(4) GRANULARITY 1,
    INDEX idx_infra_events_source source_type_name TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Service checks table
CREATE TABLE IF NOT EXISTS service_checks (
    check_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    check_name String,
    host String DEFAULT '',
    status Enum8('ok' = 0, 'warning' = 1, 'critical' = 2, 'unknown' = 3),
    timestamp DateTime64(3, 'UTC'),
    tags Map(String, String),
    message String DEFAULT '',
    INDEX idx_svc_checks_name check_name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_svc_checks_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_svc_checks_status status TYPE set(4) GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, check_name, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
