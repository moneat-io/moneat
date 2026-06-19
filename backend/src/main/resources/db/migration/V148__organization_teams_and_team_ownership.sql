-- Organization-scoped teams for resource ownership and on-call routing.
ALTER TABLE pricing_tier_configs
    ADD COLUMN IF NOT EXISTS teams_enabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE pricing_tier_configs
SET teams_enabled = tier_name IN ('TEAM', 'BUSINESS');

CREATE TABLE organization_teams (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description TEXT,
    slack_channel VARCHAR(200),
    repository VARCHAR(300),
    on_call_schedule_id INTEGER REFERENCES on_call_schedules(id) ON DELETE SET NULL,
    escalation_policy_id INTEGER REFERENCES escalation_policies(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, slug)
);

CREATE INDEX idx_organization_teams_org ON organization_teams(organization_id);

CREATE TABLE organization_team_members (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    team_id INTEGER NOT NULL REFERENCES organization_teams(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (team_id, user_id)
);

CREATE INDEX idx_organization_team_members_user ON organization_team_members(user_id);

-- Existing free-text ownership usage is negligible. Discard old claims and
-- replace the persisted shape with a resource-to-team mapping.
DELETE FROM resource_ownership;

ALTER TABLE resource_ownership
    DROP COLUMN IF EXISTS team,
    DROP COLUMN IF EXISTS oncall,
    DROP COLUMN IF EXISTS slack,
    DROP COLUMN IF EXISTS repo,
    ADD COLUMN IF NOT EXISTS team_id INTEGER REFERENCES organization_teams(id) ON DELETE CASCADE;

ALTER TABLE resource_ownership
    ALTER COLUMN team_id SET NOT NULL;

CREATE INDEX idx_resource_ownership_team ON resource_ownership(team_id);
