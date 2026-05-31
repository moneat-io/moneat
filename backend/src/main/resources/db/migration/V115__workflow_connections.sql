-- Connection & secret vault for workflows (Enterprise feature: workflows_advanced).
-- Secrets are stored with envelope encryption keyed by WORKFLOWS_CONNECTION_KEK
-- (never DATA_SOURCE_ENCRYPTION_KEY). Plaintext secrets are never persisted and
-- are only ever decrypted inside the trusted connection-resolution activity.

CREATE TABLE IF NOT EXISTS workflow_connections (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    type VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    identifier_tags JSONB NOT NULL DEFAULT '{}'::jsonb,
    encrypted_credentials TEXT NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    last_four VARCHAR(8),
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, name)
);

CREATE INDEX IF NOT EXISTS idx_workflow_connections_org_type
    ON workflow_connections (organization_id, type);

CREATE INDEX IF NOT EXISTS idx_workflow_connections_key_id
    ON workflow_connections (key_id);

CREATE TABLE IF NOT EXISTS workflow_connection_groups (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    connection_type VARCHAR(64) NOT NULL,
    member_connection_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    selection_rule JSONB NOT NULL DEFAULT '{"strategy":"first_match"}'::jsonb,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, name)
);

CREATE INDEX IF NOT EXISTS idx_workflow_connection_groups_org_type
    ON workflow_connection_groups (organization_id, connection_type);
