-- Recreate logs table with optimized structure for org-scoped queries.
-- Key changes:
--   ORDER BY  (organization_id, service, timestamp, log_id)
--   PARTITION BY (organization_id, toYYYYMMDD(timestamp))
--   LowCardinality on low-distinct-value string columns
--   index_name column for future log-index routing
--   Bloom filter on trace_id for trace-to-log correlation

CREATE TABLE IF NOT EXISTS logs_new (
    log_id UUID,
    organization_id UInt64,
    system_id UUID,
    timestamp DateTime64(3, 'UTC'),
    received_at DateTime64(3, 'UTC') DEFAULT now64(3),
    level Enum8('trace'=1,'debug'=2,'info'=3,'warn'=4,'error'=5,'fatal'=6),
    message String,
    body String,
    service LowCardinality(String),
    environment LowCardinality(String),
    host LowCardinality(String),
    source Enum8('sdk'=1,'agent_stdout'=2,'agent_stderr'=3,'otlp'=4,'datadog'=5),
    container_name LowCardinality(String),
    container_id String,
    container_image LowCardinality(String),
    trace_id String,
    span_id String,
    tags Map(String, String),
    resource_attributes Map(String, String),
    index_name LowCardinality(String) DEFAULT '',
    INDEX idx_message message TYPE tokenbf_v1(30720, 3, 0) GRANULARITY 1,
    INDEX idx_body body TYPE tokenbf_v1(30720, 3, 0) GRANULARITY 1,
    INDEX idx_trace_id trace_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_host host TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY (organization_id, toYYYYMMDD(timestamp))
ORDER BY (organization_id, service, timestamp, log_id)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

INSERT INTO logs_new
SELECT
    log_id, organization_id, system_id, timestamp, received_at,
    level, message, body, service, environment, host, source,
    container_name, container_id, container_image,
    trace_id, span_id, tags, resource_attributes,
    '' AS index_name
FROM logs;

RENAME TABLE logs TO logs_old, logs_new TO logs;

DROP TABLE IF EXISTS logs_old;
