-- Migration: Add uptime monitoring feature
-- Description: Add uptime_monitors table for external service/endpoint monitoring

CREATE TABLE IF NOT EXISTS uptime_monitors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,  -- http, keyword, json_query, tcp, ping, dns, websocket, push, docker, database, ssl
    active BOOLEAN NOT NULL DEFAULT true,

    -- Connection target
    url TEXT,                       -- HTTP/WS/Push URL
    hostname VARCHAR(255),          -- TCP/Ping/DNS/SSL target
    port INTEGER,                   -- TCP port
    
    -- HTTP options
    method VARCHAR(10) DEFAULT 'GET',
    headers JSONB,                  -- Custom headers as JSON
    body TEXT,                      -- Request body
    auth_method VARCHAR(20),        -- none, basic, bearer, ntlm
    auth_user VARCHAR(255),
    auth_pass VARCHAR(255),
    expected_status_codes TEXT,     -- Comma-separated: "200,201,301"
    max_redirects INTEGER DEFAULT 10,
    ignore_tls BOOLEAN DEFAULT false,

    -- Keyword monitor
    keyword VARCHAR(500),
    keyword_inverse BOOLEAN DEFAULT false,

    -- JSON Query monitor
    json_path VARCHAR(500),
    json_expected_value TEXT,

    -- DNS options
    dns_record_type VARCHAR(10),    -- A, AAAA, MX, CNAME, TXT, etc.
    dns_expected_value TEXT,
    dns_server VARCHAR(255),

    -- SSL options
    ssl_expiry_warn_days INTEGER DEFAULT 30,

    -- Database options
    db_connection_string TEXT,
    db_query TEXT,

    -- Docker options
    docker_container_name VARCHAR(255),
    docker_host VARCHAR(255),

    -- Check config
    interval_seconds INTEGER NOT NULL DEFAULT 60,
    timeout_seconds INTEGER NOT NULL DEFAULT 30,
    retries INTEGER NOT NULL DEFAULT 0,
    retry_interval_seconds INTEGER NOT NULL DEFAULT 60,

    -- Status tracking
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- up, down, pending, paused, maintenance
    last_check_at TIMESTAMP,
    last_status_change_at TIMESTAMP,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,

    -- Push monitor token
    push_token VARCHAR(64),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_uptime_monitors_org ON uptime_monitors(organization_id);
CREATE INDEX idx_uptime_monitors_active ON uptime_monitors(organization_id, active);
CREATE INDEX idx_uptime_monitors_next_check ON uptime_monitors(active, last_check_at, interval_seconds) WHERE active = true;
CREATE UNIQUE INDEX idx_uptime_monitors_push_token ON uptime_monitors(push_token) WHERE push_token IS NOT NULL;
