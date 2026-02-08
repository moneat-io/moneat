-- Mirror of V14__add_per_type_quotas.sql for internal plan tracking.
-- Flyway applies the versioned migration file.

ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS monthly_error_limit BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS monthly_transaction_limit BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS monthly_replay_limit BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS monthly_feedback_limit BIGINT NOT NULL DEFAULT 0;

UPDATE pricing_tier_configs
SET monthly_error_limit = monthly_unit_limit
WHERE monthly_error_limit = 0;

ALTER TABLE org_usage_counters
    ADD COLUMN IF NOT EXISTS used_errors BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_transactions BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_replays BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_feedback BIGINT NOT NULL DEFAULT 0;

UPDATE org_usage_counters
SET used_errors = used_units
WHERE used_errors = 0
  AND used_units > 0;
