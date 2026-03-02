ALTER TABLE org_usage_counters ADD COLUMN IF NOT EXISTS used_profiler_bytes BIGINT NOT NULL DEFAULT 0;
