-- Processes table
CREATE TABLE IF NOT EXISTS processes (
    process_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    host String,
    pid UInt32,
    name String DEFAULT '',
    command String DEFAULT '',
    user String DEFAULT '',
    cpu_percent Float64 DEFAULT 0,
    mem_rss UInt64 DEFAULT 0,
    mem_vms UInt64 DEFAULT 0,
    state String DEFAULT '',
    thread_count UInt32 DEFAULT 0,
    open_fd_count UInt32 DEFAULT 0,
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC'),
    INDEX idx_proc_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_proc_name name TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(timestamp)
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, host, pid, timestamp)
TTL toDateTime(timestamp) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

-- Containers table
CREATE TABLE IF NOT EXISTS containers (
    container_id_hash UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    host String,
    container_id String,
    name String DEFAULT '',
    image String DEFAULT '',
    state String DEFAULT 'running',
    cpu_percent Float64 DEFAULT 0,
    mem_usage UInt64 DEFAULT 0,
    mem_limit UInt64 DEFAULT 0,
    net_rx_bytes UInt64 DEFAULT 0,
    net_tx_bytes UInt64 DEFAULT 0,
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC'),
    INDEX idx_cont_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_cont_name name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_cont_image image TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(timestamp)
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, host, container_id, timestamp)
TTL toDateTime(timestamp) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

-- Network connections table
CREATE TABLE IF NOT EXISTS network_connections (
    connection_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    host String,
    pid UInt32 DEFAULT 0,
    local_addr String DEFAULT '',
    local_port UInt16 DEFAULT 0,
    remote_addr String DEFAULT '',
    remote_port UInt16 DEFAULT 0,
    protocol Enum8('tcp' = 1, 'udp' = 2, 'tcp6' = 3, 'udp6' = 4),
    family Enum8('IPv4' = 1, 'IPv6' = 2),
    direction String DEFAULT '',
    bytes_sent UInt64 DEFAULT 0,
    bytes_recv UInt64 DEFAULT 0,
    tags Map(String, String),
    timestamp DateTime64(3, 'UTC'),
    INDEX idx_conn_host host TYPE bloom_filter GRANULARITY 1,
    INDEX idx_conn_remote remote_addr TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, host, timestamp)
TTL toDateTime(timestamp) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;
