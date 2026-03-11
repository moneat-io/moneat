-- Remove orphaned demo log data from V7 migration.
-- V7 inserted demo logs with project_id but no organization_id (column didn't exist yet).
-- When V15 added organization_id with DEFAULT 0, these rows got organization_id = 0.
-- No legitimate logs should have organization_id = 0 (all ingestion paths require auth).

ALTER TABLE logs DELETE WHERE organization_id = 0;
