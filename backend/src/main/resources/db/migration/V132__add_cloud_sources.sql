CREATE TABLE IF NOT EXISTS cloud_sources (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    provider VARCHAR(16) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    account_id VARCHAR(64),
    role_name VARCHAR(128),
    project_id VARCHAR(128),
    tenant_id VARCHAR(128),
    subscription_id VARCHAR(128),
    billing_export_table VARCHAR(512),
    external_id VARCHAR(64) NOT NULL,
    collect_metrics BOOLEAN NOT NULL DEFAULT TRUE,
    collect_inventory BOOLEAN NOT NULL DEFAULT TRUE,
    collect_cost BOOLEAN NOT NULL DEFAULT FALSE,
    collect_logs BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    last_sync_at TIMESTAMP,
    last_error TEXT,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cloud_sources_org_provider
    ON cloud_sources (organization_id, provider);
