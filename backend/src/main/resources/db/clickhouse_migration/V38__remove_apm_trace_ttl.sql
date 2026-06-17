-- Remove hardcoded APM trace TTL so tier-configured retention is enforced
-- by RetentionBackgroundService for each organization.

ALTER TABLE apm_spans REMOVE TTL;

ALTER TABLE trace_stats REMOVE TTL;
