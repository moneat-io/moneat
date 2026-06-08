-- Pre-aggregated APM service-map data for read-side dependency maps.
--
-- The service map should not build relationships by joining raw apm_spans at
-- request time. These rollups are populated by ingestion adapters once a trace
-- batch has already been parsed into spans.

CREATE TABLE IF NOT EXISTS apm_service_stats_hourly (
    organization_id UInt64,
    bucket_start DateTime('UTC'),
    service LowCardinality(String),
    env LowCardinality(String),
    source LowCardinality(String),
    span_count UInt64,
    error_count UInt64,
    duration_sum UInt64,
    duration_count UInt64
) ENGINE = SummingMergeTree((span_count, error_count, duration_sum, duration_count))
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, bucket_start, service, env, source)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS apm_service_edges_hourly (
    organization_id UInt64,
    bucket_start DateTime('UTC'),
    from_service LowCardinality(String),
    to_service LowCardinality(String),
    env LowCardinality(String),
    source LowCardinality(String),
    call_count UInt64,
    error_count UInt64,
    duration_sum UInt64,
    duration_count UInt64
) ENGINE = SummingMergeTree((call_count, error_count, duration_sum, duration_count))
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, bucket_start, from_service, to_service, env, source)
SETTINGS index_granularity = 8192;
