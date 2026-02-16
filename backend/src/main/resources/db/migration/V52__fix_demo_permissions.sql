-- V52: Fix demo user permissions and add comprehensive demo data

-- Ensure demo user has membership in demo org (should already exist from V50, but reinsert if needed)
INSERT INTO memberships (user_id, organization_id, role, created_at)
VALUES (-1, -1, 'owner', NOW())
ON CONFLICT (user_id, organization_id) DO NOTHING;

-- Add comprehensive demo data to ClickHouse tables
-- This will be executed via ClickHouseClient in the migration runner
