-- Datadog-compatible APM spans table
CREATE TABLE IF NOT EXISTS dd_spans (
    span_id UInt64,
    trace_id UInt64,
    parent_id UInt64,
    organization_id UInt64,
    name String,
    service String,
    resource String,
    type String DEFAULT '',
    start DateTime64(9, 'UTC'),
    duration UInt64,
    error UInt8 DEFAULT 0,
    meta Map(String, String),
    metrics Map(String, Float64),
    host String DEFAULT '',
    env String DEFAULT '',
    version String DEFAULT '',
    INDEX idx_dd_spans_service service TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dd_spans_resource resource TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dd_spans_name name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dd_spans_host host TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(start)
ORDER BY (organization_id, service, start, trace_id)
TTL toDateTime(start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Datadog-compatible trace stats table (pre-aggregated)
CREATE TABLE IF NOT EXISTS dd_trace_stats (
    organization_id UInt64,
    service String,
    resource String,
    type String DEFAULT '',
    name String DEFAULT '',
    http_status_code UInt16 DEFAULT 0,
    synthetics UInt8 DEFAULT 0,
    start DateTime64(3, 'UTC'),
    duration UInt64 DEFAULT 0,
    hits UInt64 DEFAULT 0,
    top_level_hits UInt64 DEFAULT 0,
    errors UInt64 DEFAULT 0,
    ok_summary_count UInt64 DEFAULT 0,
    ok_summary_sum Float64 DEFAULT 0,
    error_summary_count UInt64 DEFAULT 0,
    error_summary_sum Float64 DEFAULT 0,
    INDEX idx_dd_trace_stats_service service TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dd_trace_stats_resource resource TYPE bloom_filter GRANULARITY 1
) ENGINE = SummingMergeTree((hits, top_level_hits, errors, ok_summary_count, ok_summary_sum, error_summary_count, error_summary_sum))
PARTITION BY toYYYYMM(start)
ORDER BY (organization_id, service, resource, name, type, http_status_code, synthetics, start)
TTL toDateTime(start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
