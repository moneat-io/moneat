-- Five-minute log-rate rollup for bounded moving-baseline security anomaly rules.
-- Populated only by new log ingestion after this migration lands.

CREATE TABLE IF NOT EXISTS security_log_rate_rollup_5m (
    organization_id UInt64,
    bucket_start DateTime64(3, 'UTC'),
    service LowCardinality(String),
    environment LowCardinality(String),
    host LowCardinality(String),
    level LowCardinality(String),
    source LowCardinality(String),
    event_count_state AggregateFunction(sum, UInt64)
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, bucket_start, service, environment, host, level, source)
TTL toDateTime(bucket_start) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

CREATE MATERIALIZED VIEW IF NOT EXISTS security_log_rate_rollup_5m_mv
TO security_log_rate_rollup_5m
AS
SELECT
    organization_id,
    toStartOfInterval(timestamp, INTERVAL 5 MINUTE) AS bucket_start,
    service,
    environment,
    host,
    toString(level) AS level,
    toString(source) AS source,
    sumState(toUInt64(1)) AS event_count_state
FROM logs
GROUP BY organization_id, bucket_start, service, environment, host, level, source;
