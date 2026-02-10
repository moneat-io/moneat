-- ClickHouse Migration: Add uptime heartbeats table
-- Description: Store uptime check results for external service monitoring

CREATE TABLE IF NOT EXISTS uptime_heartbeats (
    monitor_id UUID,
    timestamp DateTime64(3, 'UTC'),
    status UInt8,           -- 1=up, 0=down, 2=pending
    response_time_ms Int32, -- -1 if failed/not applicable
    status_code Int16,      -- HTTP status code (0 if N/A)
    message String,         -- Error message or status text
    ping_ms Float32         -- Ping latency (for ping monitors, -1 if N/A)
) ENGINE = MergeTree()
ORDER BY (monitor_id, timestamp)
TTL timestamp + INTERVAL 90 DAY;
