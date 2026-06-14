-- Per-resource ownership claims for the Resource Catalog.
-- Keyed by the catalog's stable resource id (e.g. "service:7:checkout-api"), so a
-- claim survives across catalog rebuilds and is independent of any int surrogate.
CREATE TABLE resource_ownership (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    resource_id VARCHAR(512) NOT NULL,
    team VARCHAR(200) NOT NULL,
    oncall VARCHAR(200) NOT NULL DEFAULT '',
    slack VARCHAR(200) NOT NULL DEFAULT '',
    repo VARCHAR(300) NOT NULL DEFAULT '',
    updated_by VARCHAR(320) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, resource_id)
);

CREATE INDEX idx_resource_ownership_org ON resource_ownership(organization_id);
