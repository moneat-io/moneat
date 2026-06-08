CREATE TABLE IF NOT EXISTS log_metric_points (
    organization_id UInt64,
    metric_name LowCardinality(String),
    rule_id UInt32,
    timestamp DateTime64(3, 'UTC'),
    group_key LowCardinality(String),
    group_value String,
    value Float64,
    source_query String,
    created_at DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = ReplacingMergeTree(created_at)
PARTITION BY (organization_id, toYYYYMM(timestamp))
ORDER BY (organization_id, metric_name, rule_id, timestamp, group_key, group_value)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
