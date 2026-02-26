-- Miscellaneous Datadog agent tables

-- Symbol database for dynamic instrumentation
CREATE TABLE IF NOT EXISTS symbol_db (
    symbol_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    service String DEFAULT '',
    env String DEFAULT '',
    language String DEFAULT '',
    version String DEFAULT '',
    symbols String DEFAULT '',
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_symdb_service service TYPE bloom_filter GRANULARITY 1,
    INDEX idx_symdb_lang language TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(timestamp)
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, service, env, language, version)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Log pipeline processing stats
CREATE TABLE IF NOT EXISTS pipeline_stats (
    stat_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    pipeline_id String DEFAULT '',
    stage_name String DEFAULT '',
    in_count UInt64 DEFAULT 0,
    out_count UInt64 DEFAULT 0,
    drop_count UInt64 DEFAULT 0,
    error_count UInt64 DEFAULT 0,
    host String DEFAULT '',
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_pipe_id pipeline_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_pipe_host host TYPE bloom_filter GRANULARITY 1
) ENGINE = SummingMergeTree((in_count, out_count, drop_count, error_count))
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, pipeline_id, stage_name, host)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Data lineage (OpenLineage events)
CREATE TABLE IF NOT EXISTS data_lineage (
    lineage_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    run_id String DEFAULT '',
    job_name String DEFAULT '',
    namespace String DEFAULT '',
    inputs Array(String),
    outputs Array(String),
    event_type String DEFAULT '',
    facets String DEFAULT '',
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_lineage_job job_name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_lineage_ns namespace TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, namespace, job_name, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Data Streams monitoring messages
CREATE TABLE IF NOT EXISTS data_streams (
    message_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    pipeline_id String DEFAULT '',
    stage_name String DEFAULT '',
    latency_ns UInt64 DEFAULT 0,
    payload_size UInt64 DEFAULT 0,
    direction Enum8('in' = 1, 'out' = 2) DEFAULT 'in',
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_ds_pipeline pipeline_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_ds_stage stage_name TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, pipeline_id, stage_name, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- SBOM (Software Bill of Materials) packages
CREATE TABLE IF NOT EXISTS sbom_packages (
    package_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    host String DEFAULT '',
    container_id String DEFAULT '',
    image_name String DEFAULT '',
    package_name String DEFAULT '',
    package_version String DEFAULT '',
    package_type String DEFAULT '',
    cve_ids Array(String),
    tags Map(String, String),
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_sbom_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_sbom_pkg package_name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_sbom_image image_name TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, host, container_id, package_name, package_version)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Container image metadata
CREATE TABLE IF NOT EXISTS container_images (
    image_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    image_name String DEFAULT '',
    image_tag String DEFAULT '',
    digest String DEFAULT '',
    registry String DEFAULT '',
    size_bytes UInt64 DEFAULT 0,
    os String DEFAULT '',
    architecture String DEFAULT '',
    layers UInt32 DEFAULT 0,
    tags Map(String, String),
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_cimg_name image_name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_cimg_registry registry TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, image_name, image_tag, digest)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- DBM metadata (schema snapshots and explain plans)
CREATE TABLE IF NOT EXISTS dbm_metadata (
    metadata_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    host String DEFAULT '',
    db_system String DEFAULT '',
    schema_json String DEFAULT '',
    explain_plan_hash String DEFAULT '',
    explain_plan String DEFAULT '',
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_dbmmeta_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dbmmeta_system db_system TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, host, db_system, explain_plan_hash)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- DBM agent health checks
CREATE TABLE IF NOT EXISTS dbm_health (
    health_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    host String DEFAULT '',
    db_system String DEFAULT '',
    agent_version String DEFAULT '',
    status Enum8('ok' = 1, 'warn' = 2, 'error' = 3) DEFAULT 'ok',
    checks_run UInt32 DEFAULT 0,
    checks_failed UInt32 DEFAULT 0,
    host_name String DEFAULT '',
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_dbmh_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dbmh_status status TYPE set(3) GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, host, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
