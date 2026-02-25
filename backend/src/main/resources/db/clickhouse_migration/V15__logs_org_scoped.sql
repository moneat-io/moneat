-- Add organization_id column to logs table for org-scoped log storage.
-- Logs are now associated with organizations instead of projects.
-- DEFAULT 0 serves as legacy/unscoped sentinel for historical rows.

ALTER TABLE logs
ADD COLUMN IF NOT EXISTS organization_id UInt64 DEFAULT 0;

-- Add index for efficient org-scoped log queries
ALTER TABLE logs
ADD INDEX IF NOT EXISTS idx_logs_organization_id organization_id TYPE bloom_filter GRANULARITY 1;

-- Materialize index for existing data (monitor via system.mutations)
ALTER TABLE logs MATERIALIZE INDEX IF EXISTS idx_logs_organization_id;
