CREATE TABLE dd_hosts (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    hostname VARCHAR(512) NOT NULL,
    os VARCHAR(128) DEFAULT '',
    platform VARCHAR(128) DEFAULT '',
    processor VARCHAR(256) DEFAULT '',
    cpu_cores INTEGER DEFAULT 0,
    memory_total_kb BIGINT DEFAULT 0,
    agent_version VARCHAR(64) DEFAULT '',
    gohai TEXT DEFAULT '',
    tags JSONB DEFAULT '{}',
    first_seen_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, hostname)
);

CREATE INDEX idx_dd_hosts_org ON dd_hosts(organization_id);
CREATE INDEX idx_dd_hosts_last_seen ON dd_hosts(last_seen_at);
