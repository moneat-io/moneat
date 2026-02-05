-- Migration: Add release stats columns for auto-detection and analytics
-- Enables tracking first_seen, last_seen, event counts, and auto-detection source

ALTER TABLE releases ADD COLUMN IF NOT EXISTS first_seen BIGINT;
ALTER TABLE releases ADD COLUMN IF NOT EXISTS last_seen BIGINT;
ALTER TABLE releases ADD COLUMN IF NOT EXISTS event_count BIGINT DEFAULT 0;
ALTER TABLE releases ADD COLUMN IF NOT EXISTS is_auto_detected BOOLEAN DEFAULT false;
