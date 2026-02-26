-- Synthetic monitoring results

CREATE TABLE IF NOT EXISTS synthetic_results (
    result_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    test_id String DEFAULT '',
    test_name String DEFAULT '',
    test_type Enum8('api' = 1, 'browser' = 2, 'multistep' = 3) DEFAULT 'api',
    status Enum8('passed' = 1, 'failed' = 2, 'skipped' = 3) DEFAULT 'passed',
    probe_dc String DEFAULT '',
    duration_ms UInt64 DEFAULT 0,
    error_message String DEFAULT '',
    timings Map(String, Float64),
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_synth_test test_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_synth_status status TYPE set(3) GRANULARITY 1,
    INDEX idx_synth_probe probe_dc TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, test_id, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
