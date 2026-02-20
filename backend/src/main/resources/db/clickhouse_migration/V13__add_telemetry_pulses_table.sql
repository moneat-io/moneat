-- Stores anonymous telemetry pulses received from self-hosted Moneat deployments.
-- Each pulse is one heartbeat from a self-hosted instance (sent every 4 hours by default).

CREATE TABLE IF NOT EXISTS moneat.telemetry_pulses (
    received_at     DateTime64(3, 'UTC') DEFAULT now64(3),
    deployment_id   String,
    cpu_count       UInt16 DEFAULT 0,
    mem_total_bytes UInt64 DEFAULT 0,
    mem_used_bytes  UInt64 DEFAULT 0,
    os_name         LowCardinality(String) DEFAULT '',
    os_arch         LowCardinality(String) DEFAULT '',
    jvm_version     LowCardinality(String) DEFAULT '',
    project_count   UInt64 DEFAULT 0,
    user_count      UInt64 DEFAULT 0,
    event_count     UInt64 DEFAULT 0,
    issue_count     UInt64 DEFAULT 0,
    ssl_enabled     UInt8 DEFAULT 0
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(received_at)
ORDER BY (deployment_id, received_at)
TTL received_at + INTERVAL 2 YEAR;
