-- Add OTLP support columns to apm_spans table.
-- String ID columns for OTLP/W3C 128-bit trace IDs (existing UInt64 columns stay for Datadog compat).
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS trace_id_hex String DEFAULT '';
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS span_id_hex String DEFAULT '';
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS parent_id_hex String DEFAULT '';

-- Source discrimination (same pattern as logs.source and profiles.source)
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT 'datadog';

-- OTLP-specific span fields
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS kind LowCardinality(String) DEFAULT '';
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS status_code UInt8 DEFAULT 0;
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS status_message String DEFAULT '';
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS events String DEFAULT '[]';
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS links String DEFAULT '[]';
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS resource_attributes Map(String, String) DEFAULT map();
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS scope_name String DEFAULT '';
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS scope_version String DEFAULT '';
