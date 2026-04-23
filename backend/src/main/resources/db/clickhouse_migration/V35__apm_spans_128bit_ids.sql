-- Preserve full 128-bit OTLP trace IDs and 64-bit span/parent ids (high + low UInt64 halves).
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS trace_id_high UInt64 DEFAULT 0;
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS span_id_high UInt64 DEFAULT 0;
ALTER TABLE apm_spans ADD COLUMN IF NOT EXISTS parent_id_high UInt64 DEFAULT 0;
