ALTER TABLE projects RENAME TO services;

ALTER TABLE services RENAME CONSTRAINT projects_pkey TO services_pkey;
ALTER TABLE services RENAME CONSTRAINT projects_organization_id_fkey TO services_organization_id_fkey;
ALTER TABLE services RENAME CONSTRAINT projects_organization_id_slug_key TO services_organization_id_slug_key;

ALTER INDEX IF EXISTS idx_projects_org RENAME TO idx_services_org;
ALTER INDEX IF EXISTS idx_projects_resource_id RENAME TO idx_services_resource_id;

CREATE VIEW projects AS
SELECT
    id,
    resource_id,
    organization_id,
    name,
    slug,
    framework,
    created_at,
    updated_at
FROM services;
