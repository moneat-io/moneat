-- K8s orchestrator resources table
CREATE TABLE IF NOT EXISTS k8s_resources (
    resource_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    uid String,
    resource_type String,
    namespace String DEFAULT '',
    name String,
    cluster_name String DEFAULT '',
    cluster_id String DEFAULT '',
    status String DEFAULT '',
    tags Map(String, String),
    labels Map(String, String),
    annotations Map(String, String),
    resource_version String DEFAULT '',
    creation_timestamp DateTime64(3, 'UTC') DEFAULT now(),
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_k8s_type resource_type TYPE bloom_filter GRANULARITY 1,
    INDEX idx_k8s_namespace namespace TYPE bloom_filter GRANULARITY 1,
    INDEX idx_k8s_name name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_k8s_cluster cluster_name TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, cluster_name, resource_type, namespace, uid)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- K8s manifest storage
CREATE TABLE IF NOT EXISTS k8s_manifests (
    manifest_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    uid String,
    resource_type String,
    namespace String DEFAULT '',
    name String,
    cluster_name String DEFAULT '',
    manifest String,
    content_type String DEFAULT 'application/json',
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_k8s_manif_type resource_type TYPE bloom_filter GRANULARITY 1,
    INDEX idx_k8s_manif_name name TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, cluster_name, resource_type, namespace, uid)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- DBM query samples table
CREATE TABLE IF NOT EXISTS dbm_queries (
    query_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    db_host String DEFAULT '',
    db_system String DEFAULT '',
    db_name String DEFAULT '',
    db_user String DEFAULT '',
    query_signature String DEFAULT '',
    resource_hash String DEFAULT '',
    statement String DEFAULT '',
    query_truncated Enum8('not_truncated' = 0, 'truncated' = 1) DEFAULT 'not_truncated',
    duration_ns UInt64 DEFAULT 0,
    rows_affected Int64 DEFAULT 0,
    error_code Int32 DEFAULT 0,
    error_message String DEFAULT '',
    timestamp DateTime64(3, 'UTC'),
    host String DEFAULT '',
    env String DEFAULT '',
    service String DEFAULT '',
    tags Map(String, String),
    INDEX idx_dbm_queries_host db_host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dbm_queries_system db_system TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dbm_queries_sig query_signature TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, db_host, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- DBM metrics table (pre-aggregated query stats)
CREATE TABLE IF NOT EXISTS dbm_metrics (
    organization_id UInt64,
    db_host String DEFAULT '',
    db_system String DEFAULT '',
    db_name String DEFAULT '',
    query_signature String DEFAULT '',
    timestamp DateTime64(3, 'UTC'),
    calls UInt64 DEFAULT 0,
    total_time_ns UInt64 DEFAULT 0,
    rows UInt64 DEFAULT 0,
    shared_blks_hit UInt64 DEFAULT 0,
    shared_blks_read UInt64 DEFAULT 0,
    host String DEFAULT '',
    env String DEFAULT '',
    tags Map(String, String),
    INDEX idx_dbm_metrics_host db_host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dbm_metrics_sig query_signature TYPE bloom_filter GRANULARITY 1
) ENGINE = SummingMergeTree((calls, total_time_ns, rows, shared_blks_hit, shared_blks_read))
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, db_host, db_name, query_signature, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- DBM activity table (currently executing queries)
CREATE TABLE IF NOT EXISTS dbm_activity (
    activity_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    db_host String DEFAULT '',
    db_system String DEFAULT '',
    db_name String DEFAULT '',
    db_user String DEFAULT '',
    query_signature String DEFAULT '',
    statement String DEFAULT '',
    state String DEFAULT '',
    wait_event_type String DEFAULT '',
    wait_event String DEFAULT '',
    blocking_pids Array(Int64),
    duration_ns UInt64 DEFAULT 0,
    timestamp DateTime64(3, 'UTC'),
    host String DEFAULT '',
    env String DEFAULT '',
    tags Map(String, String),
    INDEX idx_dbm_activity_host db_host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dbm_activity_state state TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, db_host, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Debugger logs/snapshots table
CREATE TABLE IF NOT EXISTS debugger_logs (
    log_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    service String DEFAULT '',
    env String DEFAULT '',
    version String DEFAULT '',
    debugger_type Enum8('log_probe' = 1, 'snapshot' = 2, 'span_decoration' = 3, 'metric_probe' = 4) DEFAULT 'log_probe',
    probe_id String DEFAULT '',
    probe_location String DEFAULT '',
    message String DEFAULT '',
    snapshot String DEFAULT '',
    host String DEFAULT '',
    timestamp DateTime64(3, 'UTC'),
    tags Map(String, String),
    INDEX idx_debugger_service service TYPE bloom_filter GRANULARITY 1,
    INDEX idx_debugger_probe probe_id TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, service, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Debugger diagnostics table
CREATE TABLE IF NOT EXISTS debugger_diagnostics (
    diagnostic_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    service String DEFAULT '',
    env String DEFAULT '',
    runtime_id String DEFAULT '',
    probe_id String DEFAULT '',
    status Enum8('received' = 1, 'installed' = 2, 'emitting' = 3, 'error' = 4, 'blocked' = 5) DEFAULT 'received',
    error_message String DEFAULT '',
    host String DEFAULT '',
    timestamp DateTime64(3, 'UTC'),
    tags Map(String, String),
    INDEX idx_diag_service service TYPE bloom_filter GRANULARITY 1,
    INDEX idx_diag_probe probe_id TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, service, timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
