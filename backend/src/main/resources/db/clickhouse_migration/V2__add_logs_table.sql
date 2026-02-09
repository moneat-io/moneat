-- Logs table for centralized logging
CREATE TABLE IF NOT EXISTS logs (
    log_id UUID,
    project_id UInt64,
    timestamp DateTime64(3, 'UTC'),
    received_at DateTime64(3, 'UTC') DEFAULT now64(3),
    level Enum8('trace' = 1, 'debug' = 2, 'info' = 3, 'warn' = 4, 'error' = 5, 'fatal' = 6),
    message String,
    body String,
    service String,
    environment String,
    host String,
    source Enum8('sdk' = 1, 'agent_stdout' = 2, 'agent_stderr' = 3, 'otlp' = 4),
    container_name String,
    container_id String,
    container_image String,
    trace_id String,
    span_id String,
    tags Map(String, String),
    resource_attributes Map(String, String),
    INDEX idx_logs_message message TYPE tokenbf_v1(30720, 2, 0) GRANULARITY 1,
    INDEX idx_logs_body body TYPE tokenbf_v1(30720, 2, 0) GRANULARITY 1,
    INDEX idx_logs_service service TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (project_id, timestamp, log_id)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
