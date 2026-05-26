ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS apm_trace_retention_days INTEGER NOT NULL DEFAULT 30;

UPDATE pricing_tier_configs
SET apm_trace_retention_days = CASE
    WHEN retention_days > 0 THEN retention_days
    ELSE 30
END;
