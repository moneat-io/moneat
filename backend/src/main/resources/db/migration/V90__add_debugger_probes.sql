CREATE TABLE IF NOT EXISTS debugger_probes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id),
    probe_type VARCHAR(20) NOT NULL DEFAULT 'log_probe',
    service VARCHAR(255) NOT NULL,
    environment VARCHAR(255) NOT NULL DEFAULT '*',
    language VARCHAR(20) NOT NULL DEFAULT 'java',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    where_type VARCHAR(10) NOT NULL DEFAULT 'method',
    type_name VARCHAR(500),
    method_name VARCHAR(255),
    source_file VARCHAR(500),
    source_lines TEXT,
    template TEXT,
    metric_name VARCHAR(255),
    metric_kind VARCHAR(20),
    tags TEXT,
    capture_config TEXT,
    created_by INTEGER REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_debugger_probes_org_id
    ON debugger_probes (organization_id);

CREATE INDEX IF NOT EXISTS idx_debugger_probes_org_active
    ON debugger_probes (organization_id, active);

CREATE INDEX IF NOT EXISTS idx_debugger_probes_org_probe_type
    ON debugger_probes (organization_id, probe_type);

CREATE INDEX IF NOT EXISTS idx_debugger_probes_org_service_env
    ON debugger_probes (organization_id, service, environment);

CREATE INDEX IF NOT EXISTS idx_debugger_probes_org_updated_at
    ON debugger_probes (organization_id, updated_at DESC);
