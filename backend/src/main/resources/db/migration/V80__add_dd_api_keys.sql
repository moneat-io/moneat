-- DD API keys for Datadog-compatible agent intake (enterprise feature)
CREATE TABLE IF NOT EXISTS dd_api_keys (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_prefix VARCHAR(12) NOT NULL,
    created_by INTEGER REFERENCES users(id),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ,
    is_active BOOLEAN DEFAULT true
);

CREATE INDEX idx_dd_api_keys_org ON dd_api_keys(organization_id);
CREATE INDEX idx_dd_api_keys_prefix ON dd_api_keys(key_prefix);
CREATE INDEX idx_dd_api_keys_active ON dd_api_keys(organization_id, is_active);
