-- Add system_id column to logs table for infrastructure/container logs
-- This allows logs to be scoped by monitoring system (agent logs) or project (SDK logs)

ALTER TABLE logs 
ADD COLUMN IF NOT EXISTS system_id UUID DEFAULT toUUID('00000000-0000-0000-0000-000000000000');

-- Add index for efficient system-scoped log queries
ALTER TABLE logs
ADD INDEX IF NOT EXISTS idx_logs_system_id system_id TYPE bloom_filter GRANULARITY 1;
