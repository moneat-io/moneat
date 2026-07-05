ALTER TABLE organization_integrations
    ADD COLUMN IF NOT EXISTS resource_id UUID;

UPDATE organization_integrations
SET resource_id = gen_random_uuid()
WHERE resource_id IS NULL;

ALTER TABLE organization_integrations
    ALTER COLUMN resource_id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN resource_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_organization_integrations_org_resource_id
    ON organization_integrations(organization_id, resource_id);
