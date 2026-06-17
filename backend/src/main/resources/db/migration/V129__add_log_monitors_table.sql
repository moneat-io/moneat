CREATE TABLE IF NOT EXISTS log_monitors (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    query TEXT NOT NULL DEFAULT '',
    levels_json TEXT NOT NULL DEFAULT '[]',
    group_by VARCHAR(128),
    condition VARCHAR(8) NOT NULL DEFAULT '>',
    threshold DOUBLE PRECISION NOT NULL,
    warning_threshold DOUBLE PRECISION,
    window_minutes INTEGER NOT NULL DEFAULT 5,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (organization_id, name)
);

CREATE INDEX IF NOT EXISTS idx_log_monitors_org_active
    ON log_monitors(organization_id, is_active);
