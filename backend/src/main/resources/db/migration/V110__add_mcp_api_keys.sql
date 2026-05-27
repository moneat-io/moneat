CREATE TABLE IF NOT EXISTS mcp_api_keys (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_prefix VARCHAR(12) NOT NULL,
    enabled_tools TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    enabled_resources TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_mcp_api_keys_org ON mcp_api_keys(organization_id);
CREATE INDEX idx_mcp_api_keys_prefix ON mcp_api_keys(key_prefix);
CREATE INDEX idx_mcp_api_keys_active ON mcp_api_keys(organization_id, is_active);
