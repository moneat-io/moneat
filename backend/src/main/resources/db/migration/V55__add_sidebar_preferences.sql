-- Add sidebar_hidden_items column to memberships table for per-user, per-org preferences
ALTER TABLE memberships
ADD COLUMN sidebar_hidden_items TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

-- Add index for better query performance
CREATE INDEX idx_memberships_sidebar_hidden_items ON memberships USING GIN (sidebar_hidden_items);

COMMENT ON COLUMN memberships.sidebar_hidden_items IS 'Array of sidebar item keys that user has chosen to hide for this organization';
