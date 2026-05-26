CREATE TABLE IF NOT EXISTS feature_flag_evaluations (
    event_time DateTime64(3) DEFAULT now64(3),
    organization_id UInt32,
    environment String,
    flag_key String,
    variant_key String,
    value_type LowCardinality(String),
    reason LowCardinality(String),
    error_code String,
    targeting_key String,
    sdk_key_prefix String,
    key_type LowCardinality(String),
    context_json String,
    duration_ms Float64
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (organization_id, environment, flag_key, event_time)
TTL event_time + INTERVAL 180 DAY;

CREATE TABLE IF NOT EXISTS feature_flag_tracking_events (
    event_time DateTime64(3) DEFAULT now64(3),
    organization_id UInt32,
    environment String,
    event_name String,
    targeting_key String,
    flag_key Nullable(String),
    variant_key Nullable(String),
    sdk_key_prefix String,
    key_type LowCardinality(String),
    value Nullable(Float64),
    properties_json String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (organization_id, environment, event_name, event_time)
TTL event_time + INTERVAL 180 DAY;

CREATE TABLE IF NOT EXISTS feature_flag_evaluations_hourly (
    hour DateTime,
    organization_id UInt32,
    environment String,
    flag_key String,
    variant_key String,
    reason LowCardinality(String),
    evaluations AggregateFunction(count),
    unique_targets AggregateFunction(uniqCombined64, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (organization_id, environment, flag_key, variant_key, reason, hour)
TTL hour + INTERVAL 180 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS feature_flag_evaluations_hourly_mv
TO feature_flag_evaluations_hourly
AS
SELECT
    toStartOfHour(event_time) AS hour,
    organization_id,
    environment,
    flag_key,
    variant_key,
    reason,
    countState() AS evaluations,
    uniqCombined64State(targeting_key) AS unique_targets
FROM feature_flag_evaluations
GROUP BY
    hour,
    organization_id,
    environment,
    flag_key,
    variant_key,
    reason;

CREATE TABLE IF NOT EXISTS feature_flag_tracking_events_hourly (
    hour DateTime,
    organization_id UInt32,
    environment String,
    event_name String,
    flag_key Nullable(String),
    variant_key Nullable(String),
    events AggregateFunction(count),
    unique_targets AggregateFunction(uniqCombined64, String),
    total_value AggregateFunction(sum, Float64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (organization_id, environment, event_name, ifNull(flag_key, ''), ifNull(variant_key, ''), hour)
TTL hour + INTERVAL 180 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS feature_flag_tracking_events_hourly_mv
TO feature_flag_tracking_events_hourly
AS
SELECT
    toStartOfHour(event_time) AS hour,
    organization_id,
    environment,
    event_name,
    flag_key,
    variant_key,
    countState() AS events,
    uniqCombined64State(targeting_key) AS unique_targets,
    sumState(ifNull(value, 0.0)) AS total_value
FROM feature_flag_tracking_events
GROUP BY
    hour,
    organization_id,
    environment,
    event_name,
    flag_key,
    variant_key;
