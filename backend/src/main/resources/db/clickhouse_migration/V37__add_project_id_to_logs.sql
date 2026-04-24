-- Restore project_id on logs after V29 recreated the table without it.
-- Keep the schema change versioned here instead of in clickhouse_init.sql.

ALTER TABLE logs
ADD COLUMN IF NOT EXISTS project_id UInt64 DEFAULT 0 AFTER organization_id;
