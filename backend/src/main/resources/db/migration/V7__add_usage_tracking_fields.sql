-- Add event_type and bytes_ingested to usage_records
ALTER TABLE usage_records ADD COLUMN IF NOT EXISTS event_type VARCHAR(50) NOT NULL DEFAULT 'error';
ALTER TABLE usage_records ADD COLUMN IF NOT EXISTS bytes_ingested BIGINT NOT NULL DEFAULT 0;

-- Drop old unique constraint (handles both possible constraint names)
ALTER TABLE usage_records DROP CONSTRAINT IF EXISTS usage_records_organization_id_project_id_date_key;

-- Add new unique constraint for upsert (we always have project_id from ingestion)
ALTER TABLE usage_records ADD CONSTRAINT usage_records_org_proj_date_type_key
    UNIQUE (organization_id, project_id, date, event_type);

-- Add is_admin flag to users
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin BOOLEAN DEFAULT false;
