-- Network Device Monitoring (NDM) tables

-- SNMP-monitored devices
CREATE TABLE IF NOT EXISTS ndm_devices (
    device_id_hash UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    device_id String,
    ip_address String DEFAULT '',
    hostname String DEFAULT '',
    vendor String DEFAULT '',
    model String DEFAULT '',
    os_version String DEFAULT '',
    device_type String DEFAULT '',
    status String DEFAULT 'unknown',
    reachability String DEFAULT 'unknown',
    snmp_version String DEFAULT '',
    tags Map(String, String),
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_ndm_dev_ip ip_address TYPE bloom_filter GRANULARITY 1,
    INDEX idx_ndm_dev_hostname hostname TYPE bloom_filter GRANULARITY 1,
    INDEX idx_ndm_dev_vendor vendor TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, device_id)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- SNMP traps
CREATE TABLE IF NOT EXISTS ndm_traps (
    trap_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    device_ip String DEFAULT '',
    oid String DEFAULT '',
    severity String DEFAULT 'info',
    message String DEFAULT '',
    variables Map(String, String),
    received_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_ndm_trap_ip device_ip TYPE bloom_filter GRANULARITY 1,
    INDEX idx_ndm_trap_oid oid TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(received_at)
ORDER BY (organization_id, device_ip, received_at)
TTL toDateTime(received_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- NetFlow/sFlow/IPFIX records
CREATE TABLE IF NOT EXISTS ndm_flows (
    flow_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    src_ip String DEFAULT '',
    dst_ip String DEFAULT '',
    src_port UInt16 DEFAULT 0,
    dst_port UInt16 DEFAULT 0,
    protocol String DEFAULT '',
    bytes UInt64 DEFAULT 0,
    packets UInt64 DEFAULT 0,
    direction String DEFAULT '',
    flow_type Enum8('netflow' = 1, 'sflow' = 2, 'ipfix' = 3) DEFAULT 'netflow',
    tags Map(String, String),
    sampled_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_ndm_flow_src src_ip TYPE bloom_filter GRANULARITY 1,
    INDEX idx_ndm_flow_dst dst_ip TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(sampled_at)
ORDER BY (organization_id, src_ip, dst_ip, sampled_at)
TTL toDateTime(sampled_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Network path traces
CREATE TABLE IF NOT EXISTS network_paths (
    path_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    source String DEFAULT '',
    destination String DEFAULT '',
    hops Array(String),
    hop_rtts Array(Float64),
    tags Map(String, String),
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_netpath_src source TYPE bloom_filter GRANULARITY 1,
    INDEX idx_netpath_dst destination TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, source, destination, collected_at)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Network device configurations (NCM)
CREATE TABLE IF NOT EXISTS ndm_configs (
    config_id UUID DEFAULT generateUUIDv4(),
    organization_id UInt64,
    device_id String DEFAULT '',
    config_type String DEFAULT '',
    content String DEFAULT '',
    tags Map(String, String),
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_ndm_cfg_device device_id TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(collected_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (organization_id, device_id, config_type)
TTL toDateTime(collected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
