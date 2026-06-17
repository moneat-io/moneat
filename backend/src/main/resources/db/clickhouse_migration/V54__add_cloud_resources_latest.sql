CREATE TABLE IF NOT EXISTS cloud_resources_latest (
    organization_id UInt64,
    cloud_source_id UInt64,
    resource_id String,
    name String,
    resource_type LowCardinality(String),
    provider LowCardinality(String),
    account String DEFAULT '',
    region String DEFAULT '',
    health LowCardinality(String) DEFAULT 'unknown',
    tags Map(String, String),
    metadata Map(String, String),
    cpu_percent Float64 DEFAULT 0,
    mem_percent Float64 DEFAULT 0,
    monthly_usd Float64 DEFAULT 0,
    cost_trend_pct Float64 DEFAULT 0,
    first_seen DateTime64(3, 'UTC') DEFAULT now64(3),
    last_seen DateTime64(3, 'UTC') DEFAULT now64(3),
    collected_at DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, cloud_source_id, provider, resource_id)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
