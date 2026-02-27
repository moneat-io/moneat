-- Fix usage_records unique constraint to handle NULL project_id (org-level records)
-- PostgreSQL treats each NULL as distinct in UNIQUE constraints, so partial indexes
-- are needed to correctly deduplicate org-level (project_id IS NULL) records.

ALTER TABLE usage_records DROP CONSTRAINT IF EXISTS usage_records_org_proj_date_type_key;

-- Unique index for project-scoped records (project_id IS NOT NULL)
CREATE UNIQUE INDEX IF NOT EXISTS usage_records_org_proj_date_type_key
    ON usage_records (organization_id, project_id, date, event_type)
    WHERE project_id IS NOT NULL;

-- Unique index for org-level records (project_id IS NULL)
CREATE UNIQUE INDEX IF NOT EXISTS usage_records_org_null_proj_date_type_key
    ON usage_records (organization_id, date, event_type)
    WHERE project_id IS NULL;
