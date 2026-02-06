-- ClickHouse initialization script

-- Events table for error/transaction storage
CREATE TABLE IF NOT EXISTS events (
    event_id UUID,
    project_id UInt64,
    timestamp DateTime64(3, 'UTC'),
    received_at DateTime64(3, 'UTC') DEFAULT now64(3),
    event_type Enum8('error' = 1, 'transaction' = 2, 'session' = 3),
    level Enum8('fatal' = 1, 'error' = 2, 'warning' = 3, 'info' = 4, 'debug' = 5),
    
    -- Core fields
    message String,
    platform String,
    environment String,
    release String,
    dist String,
    server_name String,
    
    -- User context
    user_id String,
    user_email String,
    user_username String,
    user_ip_address String,
    
    -- Device/OS context
    device_name String,
    device_family String,
    device_model String,
    os_name String,
    os_version String,
    
    -- Browser/App context
    browser_name String,
    browser_version String,
    app_version String,
    
    -- Error specific
    exception_type String,
    exception_value String,
    stack_trace String,  -- JSON serialized
    
    -- Transaction specific
    transaction_name String,
    transaction_op String,
    duration_ms Float64,
    
    -- Grouping
    fingerprint Array(String),
    issue_id String,
    
    -- Tags (key-value pairs)
    tags Map(String, String),
    
    -- Full contexts JSON
    contexts String,  -- JSON blob for full context data
    breadcrumbs String,  -- JSON blob for breadcrumb trail
    request String,  -- JSON blob for request data
    
    -- SDK info
    sdk_name String,
    sdk_version String
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (project_id, timestamp, event_id)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Spans table for transaction child spans
CREATE TABLE IF NOT EXISTS spans (
    span_id String,
    parent_span_id String,
    trace_id String,
    transaction_id UUID,
    project_id UInt64,
    op String,
    description String,
    start_timestamp DateTime64(3, 'UTC'),
    end_timestamp DateTime64(3, 'UTC'),
    duration_ms Float64,
    status String,
    tags Map(String, String),
    data String
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(start_timestamp)
ORDER BY (project_id, trace_id, transaction_id, start_timestamp)
TTL toDateTime(start_timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Issues table (aggregated view)
CREATE TABLE IF NOT EXISTS issues (
    issue_id String,
    project_id UInt64,
    fingerprint Array(String),
    first_seen DateTime64(3, 'UTC'),
    last_seen DateTime64(3, 'UTC'),
    event_count UInt64,
    user_count UInt64,
    
    -- Sample event data
    title String,
    culprit String,
    level Enum8('fatal' = 1, 'error' = 2, 'warning' = 3, 'info' = 4, 'debug' = 5),
    platform String,
    
    -- Status
    status Enum8('unresolved' = 1, 'resolved' = 2, 'ignored' = 3),
    
    updated_at DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY (project_id, issue_id)
SETTINGS index_granularity = 8192;

-- Materialized view to auto-populate issues from events
CREATE MATERIALIZED VIEW IF NOT EXISTS issues_mv TO issues AS
SELECT
    issue_id,
    project_id,
    fingerprint,
    min(timestamp) as first_seen,
    max(timestamp) as last_seen,
    count() as event_count,
    uniq(user_id) as user_count,
    any(message) as title,
    any(exception_type) as culprit,
    any(level) as level,
    any(platform) as platform,
    CAST('unresolved' AS Enum8('unresolved' = 1, 'resolved' = 2, 'ignored' = 3)) as status,
    max(timestamp) as updated_at
FROM events
WHERE event_type = 'error'
GROUP BY issue_id, project_id, fingerprint;

-- Sessions table
CREATE TABLE IF NOT EXISTS sessions (
    session_id UUID,
    project_id UInt64,
    started DateTime64(3, 'UTC'),
    duration_ms Float64,
    status Enum8('ok' = 1, 'exited' = 2, 'crashed' = 3, 'abnormal' = 4),
    errors UInt32,
    release String,
    environment String,
    user_id String,
    received_at DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(started)
ORDER BY (project_id, started)
TTL toDateTime(started) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
