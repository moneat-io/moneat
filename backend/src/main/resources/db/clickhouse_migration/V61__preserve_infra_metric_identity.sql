-- Preserve per-resource metric identity for host infrastructure rollups.
-- Disk metrics can emit one row per filesystem, so host-level latest and
-- rollup tables must keep those identities distinct before dashboard and
-- alert queries collapse them to a host-level "worst filesystem" value.
--
-- Existing rows were already collapsed without filesystem identity. Preserve
-- them with an empty identity so historical views keep their current values;
-- new ingestion writes per-filesystem identities for correct future data.

DROP TABLE IF EXISTS metrics_latest_by_host_rekey_v61;

CREATE TABLE metrics_latest_by_host_rekey_v61 (
    organization_id UInt64,
    host_id UInt64,
    metric_name LowCardinality(String),
    timestamp DateTime64(3, 'UTC'),
    value Float64,
    host String DEFAULT '',
    unit String DEFAULT '',
    source LowCardinality(String) DEFAULT '',
    metric_identity String DEFAULT '',
    tags Map(String, String)
) ENGINE = ReplacingMergeTree(timestamp)
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, host_id, metric_name, metric_identity)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

INSERT INTO metrics_latest_by_host_rekey_v61 (
    organization_id,
    host_id,
    metric_name,
    timestamp,
    value,
    host,
    unit,
    source,
    metric_identity,
    tags
)
SELECT
    organization_id,
    host_id,
    metric_name,
    timestamp,
    value,
    host,
    unit,
    source,
    '',
    tags
FROM metrics_latest_by_host;

DROP TABLE IF EXISTS metrics_latest_by_host_before_v61;

RENAME TABLE
    metrics_latest_by_host TO metrics_latest_by_host_before_v61,
    metrics_latest_by_host_rekey_v61 TO metrics_latest_by_host;

DROP TABLE IF EXISTS metrics_rollup_1m_rekey_v61;

CREATE TABLE metrics_rollup_1m_rekey_v61 (
    organization_id UInt64,
    host_id UInt64,
    bucket_start DateTime64(3, 'UTC'),
    metric_name LowCardinality(String),
    host String DEFAULT '',
    unit String DEFAULT '',
    source LowCardinality(String) DEFAULT '',
    metric_identity String DEFAULT '',
    tags Map(String, String) DEFAULT map(),
    value_sum Float64,
    value_count UInt64
) ENGINE = SummingMergeTree((value_sum, value_count))
PARTITION BY toYYYYMM(bucket_start)
ORDER BY (organization_id, host_id, bucket_start, metric_name, metric_identity)
TTL toDateTime(bucket_start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

INSERT INTO metrics_rollup_1m_rekey_v61 (
    organization_id,
    host_id,
    bucket_start,
    metric_name,
    host,
    unit,
    source,
    metric_identity,
    tags,
    value_sum,
    value_count
)
SELECT
    organization_id,
    host_id,
    bucket_start,
    metric_name,
    host,
    unit,
    source,
    '',
    map(),
    value_sum,
    value_count
FROM metrics_rollup_1m;

DROP TABLE IF EXISTS metrics_rollup_1m_before_v61;

RENAME TABLE
    metrics_rollup_1m TO metrics_rollup_1m_before_v61,
    metrics_rollup_1m_rekey_v61 TO metrics_rollup_1m;
