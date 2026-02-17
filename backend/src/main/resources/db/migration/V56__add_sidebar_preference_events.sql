-- Create sidebar_preference_events table for product insight tracking
CREATE TABLE sidebar_preference_events (
    id SERIAL PRIMARY KEY,
    membership_id INTEGER NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    hidden_items TEXT[] NOT NULL,
    source VARCHAR(32) NOT NULL CHECK (source IN ('onboarding', 'settings')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes for analytics queries
CREATE INDEX idx_sidebar_events_org_created ON sidebar_preference_events(organization_id, created_at);
CREATE INDEX idx_sidebar_events_user_created ON sidebar_preference_events(user_id, created_at);
CREATE INDEX idx_sidebar_events_source ON sidebar_preference_events(source);

COMMENT ON TABLE sidebar_preference_events IS 'Historical log of sidebar preference changes for product insights';
COMMENT ON COLUMN sidebar_preference_events.source IS 'Where the preference change originated: onboarding or settings';
