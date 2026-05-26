-- Rollups for long-window APM dashboard queries.
ALTER TABLE apm_spans ADD INDEX IF NOT EXISTS idx_apm_spans_trace_id trace_id TYPE bloom_filter GRANULARITY 1;
ALTER TABLE apm_spans ADD INDEX IF NOT EXISTS idx_apm_spans_trace_id_hex trace_id_hex TYPE bloom_filter GRANULARITY 1;
ALTER TABLE apm_spans ADD INDEX IF NOT EXISTS idx_apm_spans_span_id span_id TYPE bloom_filter GRANULARITY 1;
ALTER TABLE apm_spans ADD INDEX IF NOT EXISTS idx_apm_spans_span_id_hex span_id_hex TYPE bloom_filter GRANULARITY 1;

CREATE TABLE IF NOT EXISTS apm_trace_summaries (
    organization_id UInt64,
    bucket_start DateTime('UTC'),
    service LowCardinality(String),
    env LowCardinality(String),
    trace_id_canonical String,
    root_service_state AggregateFunction(argMin, String, Tuple(UInt8, DateTime64(9, 'UTC'))),
    root_resource_state AggregateFunction(argMin, String, Tuple(UInt8, DateTime64(9, 'UTC'))),
    root_name_state AggregateFunction(argMin, String, Tuple(UInt8, DateTime64(9, 'UTC'))),
    source_state AggregateFunction(argMin, String, Tuple(UInt8, DateTime64(9, 'UTC'))),
    trace_start_state AggregateFunction(min, DateTime64(9, 'UTC')),
    trace_end_state AggregateFunction(max, DateTime64(9, 'UTC')),
    span_count_state AggregateFunction(sum, UInt64),
    error_count_state AggregateFunction(sum, UInt64)
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, bucket_start, service, env, trace_id_canonical)
SETTINGS index_granularity = 8192;

CREATE MATERIALIZED VIEW IF NOT EXISTS apm_trace_summaries_mv
TO apm_trace_summaries
AS
SELECT
    organization_id,
    toStartOfHour(start) AS bucket_start,
    service,
    env,
    if(trace_id_hex != '', trace_id_hex, toString(trace_id)) AS trace_id_canonical,
    argMinState(
        service,
        tuple(toUInt8(if(parent_id = 0 AND parent_id_high = 0 AND parent_id_hex = '', 0, 1)), start)
    ) AS root_service_state,
    argMinState(
        resource,
        tuple(toUInt8(if(parent_id = 0 AND parent_id_high = 0 AND parent_id_hex = '', 0, 1)), start)
    ) AS root_resource_state,
    argMinState(
        name,
        tuple(toUInt8(if(parent_id = 0 AND parent_id_high = 0 AND parent_id_hex = '', 0, 1)), start)
    ) AS root_name_state,
    argMinState(
        source,
        tuple(toUInt8(if(parent_id = 0 AND parent_id_high = 0 AND parent_id_hex = '', 0, 1)), start)
    ) AS source_state,
    minState(start) AS trace_start_state,
    maxState(addNanoseconds(start, toInt64(duration))) AS trace_end_state,
    sumState(toUInt64(1)) AS span_count_state,
    sumState(toUInt64(error)) AS error_count_state
FROM apm_spans
GROUP BY organization_id, bucket_start, service, env, trace_id_canonical;

CREATE TABLE IF NOT EXISTS apm_error_groups_hourly (
    organization_id UInt64,
    bucket_start DateTime('UTC'),
    service LowCardinality(String),
    resource String,
    error_type String,
    error_message String,
    error_count_state AggregateFunction(sum, UInt64),
    last_seen_state AggregateFunction(max, DateTime64(9, 'UTC')),
    sample_trace_id_state AggregateFunction(argMax, String, DateTime64(9, 'UTC'))
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, bucket_start, service, resource, error_type, error_message)
SETTINGS index_granularity = 8192;

CREATE MATERIALIZED VIEW IF NOT EXISTS apm_error_groups_hourly_mv
TO apm_error_groups_hourly
AS
SELECT
    organization_id,
    toStartOfHour(start) AS bucket_start,
    service,
    resource,
    meta['error.type'] AS error_type,
    meta['error.msg'] AS error_message,
    sumState(toUInt64(1)) AS error_count_state,
    maxState(start) AS last_seen_state,
    argMaxState(if(trace_id_hex != '', trace_id_hex, toString(trace_id)), start) AS sample_trace_id_state
FROM apm_spans
WHERE error = 1
GROUP BY organization_id, bucket_start, service, resource, error_type, error_message;

CREATE TABLE IF NOT EXISTS apm_resource_stats_hourly (
    organization_id UInt64,
    bucket_start DateTime('UTC'),
    service LowCardinality(String),
    resource String,
    name String,
    type LowCardinality(String),
    total_hits UInt64,
    total_errors UInt64,
    ok_summary_count UInt64,
    ok_summary_sum Float64
) ENGINE = SummingMergeTree((total_hits, total_errors, ok_summary_count, ok_summary_sum))
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, bucket_start, service, resource, name, type)
SETTINGS index_granularity = 8192;

CREATE MATERIALIZED VIEW IF NOT EXISTS apm_resource_stats_hourly_mv
TO apm_resource_stats_hourly
AS
SELECT
    organization_id,
    toStartOfHour(start) AS bucket_start,
    service,
    resource,
    name,
    type,
    sum(hits) AS total_hits,
    sum(errors) AS total_errors,
    sum(ok_summary_count) AS ok_summary_count,
    sum(ok_summary_sum) AS ok_summary_sum
FROM trace_stats
GROUP BY organization_id, bucket_start, service, resource, name, type;
