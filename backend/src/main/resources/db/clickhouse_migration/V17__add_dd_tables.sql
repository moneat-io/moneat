-- Datadog-compatible metrics table for user-defined named metrics with arbitrary tags
CREATE TABLE IF NOT EXISTS dd_metrics (
    metric_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    metric_name String, 
    metric_type Enum8('gauge' = 1, 'rate' = 2, 'count' = 3),
    timestamp DateTime64(3, 'UTC'),
    value Float64,
    host String DEFAULT '',
    tags Map(String, String),
    unit String DEFAULT '',
    source_type_name String DEFAULT '',
    INDEX idx_dd_metrics_name metric_name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dd_metrics_host host TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, metric_name, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Datadog-compatible sketches table for distribution metrics (DDSketch)
CREATE TABLE IF NOT EXISTS dd_sketches (
    sketch_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    metric_name String,
    timestamp DateTime64(3, 'UTC'),
    host String DEFAULT '',
    tags Map(String, String),
    sketch_count UInt64 DEFAULT 0,
    sketch_min Float64 DEFAULT 0,
    sketch_max Float64 DEFAULT 0,
    sketch_avg Float64 DEFAULT 0,
    sketch_sum Float64 DEFAULT 0,
    sketch_k Array(Int32),
    sketch_n Array(UInt32),
    INDEX idx_dd_sketches_name metric_name TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, metric_name, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
