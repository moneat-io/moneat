-- V58: Add per-type byte tracking to usage counters and log event counting
-- This enables accurate per-type overage cost calculation

ALTER TABLE org_usage_counters ADD COLUMN used_logs BIGINT NOT NULL DEFAULT 0;
ALTER TABLE org_usage_counters ADD COLUMN used_error_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE org_usage_counters ADD COLUMN used_replay_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE org_usage_counters ADD COLUMN used_log_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE org_usage_counters ADD COLUMN used_llm_bytes BIGINT NOT NULL DEFAULT 0;
