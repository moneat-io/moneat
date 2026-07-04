CREATE TABLE IF NOT EXISTS product_analytics_funnels (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    steps_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    filters_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    prop_filters_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    group_by VARCHAR(32) NOT NULL DEFAULT 'session_id',
    source VARCHAR(255),
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ,
    UNIQUE (organization_id, resource_id)
);

CREATE INDEX IF NOT EXISTS idx_product_analytics_funnels_project_active
    ON product_analytics_funnels (organization_id, project_id, archived_at, updated_at DESC);
