-- Model Slack installations independently from OAuth grants and workspace
-- bindings. Tokens are encrypted lazily by the application because Flyway does
-- not have access to the notification connector key-encryption key.
CREATE TABLE slack_installations (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    legacy_integration_id INTEGER UNIQUE REFERENCES organization_integrations(id) ON DELETE SET NULL,
    team_id VARCHAR(100),
    team_name VARCHAR(255),
    enterprise_id VARCHAR(100),
    enterprise_name VARCHAR(255),
    is_enterprise_install BOOLEAN NOT NULL DEFAULT FALSE,
    app_id VARCHAR(100),
    enabled_capabilities TEXT NOT NULL DEFAULT '',
    default_channel_id VARCHAR(255),
    default_channel_name VARCHAR(255),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_status VARCHAR(40) NOT NULL DEFAULT 'REAUTHORIZATION_REQUIRED',
    health_detail VARCHAR(500),
    last_verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_slack_installation_identity
        CHECK (team_id IS NOT NULL OR enterprise_id IS NOT NULL),
    CONSTRAINT chk_slack_enterprise_install
        CHECK (NOT is_enterprise_install OR enterprise_id IS NOT NULL),
    CONSTRAINT uq_slack_installation_org_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_slack_installation_id_org UNIQUE (id, organization_id)
);

CREATE UNIQUE INDEX uq_slack_installation_org_team
    ON slack_installations(organization_id, team_id)
    WHERE team_id IS NOT NULL;

CREATE UNIQUE INDEX uq_slack_installation_org_enterprise
    ON slack_installations(organization_id, enterprise_id)
    WHERE is_enterprise_install;

CREATE UNIQUE INDEX uq_slack_installation_default
    ON slack_installations(organization_id)
    WHERE is_default;

CREATE INDEX idx_slack_installation_org
    ON slack_installations(organization_id, enabled);

-- An Enterprise Grid org installation can exist before it is attached to any
-- workspace. Workspace bindings therefore have their own lifecycle and health.
CREATE TABLE slack_workspace_bindings (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    slack_installation_id INTEGER NOT NULL,
    team_id VARCHAR(100) NOT NULL,
    team_name VARCHAR(255),
    enterprise_id VARCHAR(100),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_status VARCHAR(40) NOT NULL DEFAULT 'REAUTHORIZATION_REQUIRED',
    health_detail VARCHAR(500),
    last_verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_slack_workspace_binding_installation
        FOREIGN KEY (slack_installation_id, organization_id)
        REFERENCES slack_installations(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT uq_slack_workspace_binding_org_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_slack_workspace_binding_org_team UNIQUE (organization_id, team_id)
);

CREATE UNIQUE INDEX uq_slack_workspace_binding_primary
    ON slack_workspace_bindings(slack_installation_id)
    WHERE is_primary;

CREATE INDEX idx_slack_workspace_binding_installation
    ON slack_workspace_bindings(slack_installation_id, enabled);

-- Bot and privileged-user tokens are distinct principals. Rotation and
-- revocation metadata is recorded per grant so a user grant can never silently
-- replace or inherit authority from the bot grant.
CREATE TABLE slack_installation_grants (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    slack_installation_id INTEGER NOT NULL,
    grant_type VARCHAR(20) NOT NULL,
    slack_user_id VARCHAR(100),
    token_type VARCHAR(40),
    access_token_ciphertext TEXT NOT NULL,
    access_token_key_id VARCHAR(100) NOT NULL,
    refresh_token_ciphertext TEXT,
    refresh_token_key_id VARCHAR(100),
    granted_scopes TEXT NOT NULL DEFAULT '',
    expires_at TIMESTAMP,
    rotated_at TIMESTAMP,
    revoked_at TIMESTAMP,
    health_status VARCHAR(40) NOT NULL DEFAULT 'REAUTHORIZATION_REQUIRED',
    health_detail VARCHAR(500),
    last_verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_slack_installation_grant_installation
        FOREIGN KEY (slack_installation_id, organization_id)
        REFERENCES slack_installations(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT chk_slack_installation_grant_type CHECK (grant_type IN ('BOT', 'USER')),
    CONSTRAINT chk_slack_user_grant_identity CHECK (grant_type <> 'USER' OR slack_user_id IS NOT NULL),
    CONSTRAINT uq_slack_installation_grant_org_resource UNIQUE (organization_id, resource_id)
);

CREATE UNIQUE INDEX uq_slack_installation_bot_grant
    ON slack_installation_grants(slack_installation_id)
    WHERE grant_type = 'BOT';

CREATE UNIQUE INDEX uq_slack_installation_user_grant
    ON slack_installation_grants(slack_installation_id)
    WHERE grant_type = 'USER';

CREATE INDEX idx_slack_installation_grant_installation
    ON slack_installation_grants(slack_installation_id, grant_type);

-- Preserve configured Slack workspaces and their default delivery channel.
-- The application encrypts and moves access_token atomically on first use.
INSERT INTO slack_installations (
    organization_id,
    legacy_integration_id,
    team_id,
    team_name,
    default_channel_id,
    default_channel_name,
    is_default,
    enabled,
    created_at,
    updated_at
)
SELECT
    oi.organization_id,
    oi.id,
    oi.team_id,
    oi.team_name,
    oi.channel_id,
    oi.channel_name,
    TRUE,
    oi.enabled,
    oi.created_at,
    oi.updated_at
FROM organization_integrations oi
WHERE oi.integration_type = 'slack'
  AND oi.access_token IS NOT NULL
  AND oi.team_id IS NOT NULL
ON CONFLICT (legacy_integration_id) DO NOTHING;

INSERT INTO slack_workspace_bindings (
    organization_id,
    slack_installation_id,
    team_id,
    team_name,
    is_primary,
    enabled,
    created_at,
    updated_at
)
SELECT
    si.organization_id,
    si.id,
    si.team_id,
    si.team_name,
    TRUE,
    si.enabled,
    si.created_at,
    si.updated_at
FROM slack_installations si
WHERE si.team_id IS NOT NULL
ON CONFLICT (organization_id, team_id) DO NOTHING;

ALTER TABLE slack_user_mappings
    DROP CONSTRAINT IF EXISTS slack_user_mappings_user_id_key;

ALTER TABLE slack_user_mappings
    ADD COLUMN slack_installation_id INTEGER REFERENCES slack_installations(id) ON DELETE CASCADE;

UPDATE slack_user_mappings sum
SET slack_installation_id = si.id
FROM memberships m, slack_installations si
WHERE m.user_id = sum.user_id
  AND si.organization_id = m.organization_id
  AND si.team_id = sum.slack_team_id;

CREATE UNIQUE INDEX uq_slack_user_mapping_installation_user
    ON slack_user_mappings(slack_installation_id, user_id)
    WHERE slack_installation_id IS NOT NULL;

CREATE INDEX idx_slack_user_mapping_installation
    ON slack_user_mappings(slack_installation_id);

ALTER TABLE on_call_schedule_usergroups
    ADD COLUMN slack_installation_id INTEGER REFERENCES slack_installations(id) ON DELETE SET NULL;

UPDATE on_call_schedule_usergroups osu
SET slack_installation_id = si.id
FROM on_call_schedules schedule
JOIN slack_installations si
  ON si.organization_id = schedule.organization_id
 AND si.is_default
WHERE schedule.id = osu.schedule_id;

CREATE INDEX idx_schedule_usergroup_slack_installation
    ON on_call_schedule_usergroups(slack_installation_id);
