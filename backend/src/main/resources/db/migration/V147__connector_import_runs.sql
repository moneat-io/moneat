CREATE TABLE IF NOT EXISTS connector_import_runs (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    installation_id INTEGER NOT NULL REFERENCES connector_installations(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    import_type VARCHAR(64) NOT NULL,
    external_project_id VARCHAR(255),
    external_resource_id VARCHAR(255),
    date_start DATE NOT NULL,
    date_end DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    rows_imported INTEGER NOT NULL DEFAULT 0,
    requested_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    queued_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    last_error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_import_runs_org_resource
    ON connector_import_runs(organization_id, resource_id);

CREATE INDEX IF NOT EXISTS idx_connector_import_runs_installation_status
    ON connector_import_runs(installation_id, status, updated_at);
