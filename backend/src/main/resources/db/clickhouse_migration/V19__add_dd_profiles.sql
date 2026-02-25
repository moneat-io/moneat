-- Datadog-compatible continuous profiling metadata table
CREATE TABLE IF NOT EXISTS dd_profiles (
    profile_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    host String DEFAULT '',
    service String DEFAULT '',
    env String DEFAULT '',
    version String DEFAULT '',
    runtime String DEFAULT '',
    language String DEFAULT '',
    profile_type String DEFAULT '',
    start_time DateTime64(3, 'UTC'),
    end_time DateTime64(3, 'UTC'),
    duration_ns UInt64 DEFAULT 0,
    storage_key String,
    tags Map(String, String),
    size_bytes UInt64 DEFAULT 0,
    INDEX idx_dd_profiles_service service TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dd_profiles_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dd_profiles_type profile_type TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(start_time)
ORDER BY (organization_id, service, start_time)
TTL toDateTime(start_time) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
