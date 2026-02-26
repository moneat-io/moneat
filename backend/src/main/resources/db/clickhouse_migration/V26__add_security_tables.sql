-- Security monitoring tables

-- CWS runtime security events
CREATE TABLE IF NOT EXISTS security_events (
    event_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    rule_id String DEFAULT '',
    rule_name String DEFAULT '',
    rule_category String DEFAULT '',
    severity Enum8('info' = 1, 'low' = 2, 'medium' = 3, 'high' = 4, 'critical' = 5) DEFAULT 'info',
    agent_rule_version String DEFAULT '',
    event_type String DEFAULT '',
    process_name String DEFAULT '',
    file_path String DEFAULT '',
    host String DEFAULT '',
    env String DEFAULT '',
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_sec_rule rule_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_sec_severity severity TYPE set(5) GRANULARITY 1,
    INDEX idx_sec_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_sec_type event_type TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, timestamp, rule_id)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- eBPF activity dumps
CREATE TABLE IF NOT EXISTS security_dumps (
    dump_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    activity_type String DEFAULT '',
    process_name String DEFAULT '',
    host String DEFAULT '',
    duration_ns UInt64 DEFAULT 0,
    dump_data String DEFAULT '',
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_dump_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_dump_type activity_type TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, timestamp, host)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- CSPM compliance findings
CREATE TABLE IF NOT EXISTS compliance_findings (
    finding_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    framework String DEFAULT '',
    rule_id String DEFAULT '',
    rule_name String DEFAULT '',
    status Enum8('passed' = 1, 'failed' = 2, 'skipped' = 3, 'error' = 4) DEFAULT 'passed',
    resource_type String DEFAULT '',
    resource_id String DEFAULT '',
    resource_name String DEFAULT '',
    tags Map(String, String),
    evaluated_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_comp_framework framework TYPE bloom_filter GRANULARITY 1,
    INDEX idx_comp_status status TYPE set(4) GRANULARITY 1,
    INDEX idx_comp_rule rule_id TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(evaluated_at)
ORDER BY (organization_id, evaluated_at, framework, rule_id)
TTL toDateTime(evaluated_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
