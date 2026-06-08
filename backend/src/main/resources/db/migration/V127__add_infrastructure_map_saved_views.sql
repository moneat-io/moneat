CREATE TABLE infrastructure_map_saved_views (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(48) NOT NULL,
    name_key VARCHAR(48) NOT NULL,
    resource_kind VARCHAR(16) NOT NULL,
    view_state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT infrastructure_map_saved_views_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT infrastructure_map_saved_views_resource_kind_check
        CHECK (resource_kind IN ('hosts', 'containers'))
);

CREATE UNIQUE INDEX idx_infrastructure_map_saved_views_name_unique
    ON infrastructure_map_saved_views (organization_id, user_id, name_key);

CREATE INDEX idx_infrastructure_map_saved_views_user
    ON infrastructure_map_saved_views (organization_id, user_id, updated_at DESC);
