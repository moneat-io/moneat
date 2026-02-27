-- Add pending_overage_bytes to accumulate byte-level ingestion overage with precision.
-- This replaces the lossy GB*100 conversion that happened at reserve time,
-- which dropped sub-10MB increments. Bytes are drained to pending_meter_units
-- (GB*100) at flush time, so small requests accumulate correctly.
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pending_overage_bytes BIGINT NOT NULL DEFAULT 0;
