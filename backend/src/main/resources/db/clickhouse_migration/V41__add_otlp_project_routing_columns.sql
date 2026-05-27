ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS project_id UInt64 DEFAULT 0 AFTER organization_id;

ALTER TABLE metrics ADD COLUMN IF NOT EXISTS project_id UInt64 DEFAULT 0 AFTER organization_id;

ALTER TABLE metric_sketches ADD COLUMN IF NOT EXISTS project_id UInt64 DEFAULT 0 AFTER organization_id;
