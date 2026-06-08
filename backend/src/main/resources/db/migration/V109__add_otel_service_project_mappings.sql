CREATE TABLE IF NOT EXISTS otel_service_project_mappings (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_namespace VARCHAR(255) NOT NULL DEFAULT '',
    service_name VARCHAR(255) NOT NULL,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_otel_service_project_mapping
        UNIQUE (organization_id, service_namespace, service_name)
);

CREATE INDEX IF NOT EXISTS idx_otel_service_project_mappings_project
    ON otel_service_project_mappings(project_id);

CREATE TABLE IF NOT EXISTS otel_observed_services (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_namespace VARCHAR(255) NOT NULL DEFAULT '',
    service_name VARCHAR(255) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    seen_logs BOOLEAN NOT NULL DEFAULT FALSE,
    seen_traces BOOLEAN NOT NULL DEFAULT FALSE,
    seen_metrics BOOLEAN NOT NULL DEFAULT FALSE,
    last_environment VARCHAR(255),
    CONSTRAINT uq_otel_observed_service
        UNIQUE (organization_id, service_namespace, service_name)
);

CREATE INDEX IF NOT EXISTS idx_otel_observed_services_org_last_seen
    ON otel_observed_services(organization_id, last_seen_at DESC);
