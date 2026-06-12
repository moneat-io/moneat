-- Public synthetic variable identifiers are UUID resource IDs; integer IDs remain internal.
ALTER TABLE synthetic_variables
    ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX IF NOT EXISTS idx_synthetic_variables_org_resource_id
    ON synthetic_variables(organization_id, resource_id);
