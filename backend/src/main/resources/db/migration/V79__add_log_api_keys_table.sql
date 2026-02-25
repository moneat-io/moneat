-- Org-level log API keys for log ingestion (replaces DSN-based auth for logs)
CREATE TABLE IF NOT EXISTS log_api_keys (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_prefix VARCHAR(12) NOT NULL,
    created_by INTEGER REFERENCES users(id),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ,
    is_active BOOLEAN DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_log_api_keys_org ON log_api_keys(organization_id);
CREATE INDEX IF NOT EXISTS idx_log_api_keys_prefix ON log_api_keys(key_prefix);
CREATE INDEX IF NOT EXISTS idx_log_api_keys_active ON log_api_keys(organization_id, is_active);

-- Drop unused log_sources table (was project-scoped, never used)
DROP TABLE IF EXISTS log_sources;
