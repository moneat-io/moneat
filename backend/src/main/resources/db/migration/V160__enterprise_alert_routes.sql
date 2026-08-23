-- Enterprise Alert Routes.
--
-- Versioned, organization-scoped routing over a normalized source-neutral alert context.
-- These tables are independent of the AGPL incident-provider routing rules stored in
-- incident_routing_rules, which continue to forward alerts to external providers.

CREATE TABLE enterprise_alert_routes (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    route_key VARCHAR(160) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    position INTEGER NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    updated_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, route_key),
    UNIQUE (organization_id, position),
    CONSTRAINT chk_enterprise_alert_routes_position CHECK (position >= 0),
    CONSTRAINT chk_enterprise_alert_routes_revision CHECK (revision > 0)
);

CREATE INDEX idx_enterprise_alert_routes_evaluation_order
    ON enterprise_alert_routes(organization_id, position, id);

CREATE TABLE enterprise_alert_route_condition_groups (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    route_id INTEGER NOT NULL REFERENCES enterprise_alert_routes(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (route_id, position),
    CONSTRAINT chk_enterprise_alert_route_groups_position CHECK (position >= 0)
);

CREATE TABLE enterprise_alert_route_conditions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    group_id INTEGER NOT NULL REFERENCES enterprise_alert_route_condition_groups(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    context_field VARCHAR(48) NOT NULL,
    metadata_key VARCHAR(160),
    operator VARCHAR(32) NOT NULL,
    comparison_values JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (group_id, position),
    CONSTRAINT chk_enterprise_alert_route_conditions_position CHECK (position >= 0)
);

CREATE TABLE enterprise_alert_route_actions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    route_id INTEGER NOT NULL REFERENCES enterprise_alert_routes(id) ON DELETE CASCADE,
    paging_cadence VARCHAR(32) NOT NULL DEFAULT 'NONE',
    grouping_behavior VARCHAR(24) NOT NULL DEFAULT 'SUGGESTED',
    grouping_window_kind VARCHAR(16) NOT NULL DEFAULT 'ROLLING',
    grouping_window_seconds INTEGER NOT NULL DEFAULT 900,
    grouping_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    incident_creation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    incident_initial_status VARCHAR(24) NOT NULL DEFAULT 'TRIAGE',
    incident_title_template TEXT,
    incident_summary_template TEXT,
    incident_severity VARCHAR(20),
    incident_type VARCHAR(120),
    recovery_cancel_escalations BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_decline_unaccepted_triage BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_grace_seconds INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (route_id),
    CONSTRAINT chk_enterprise_alert_route_paging_cadence CHECK (
        paging_cadence IN ('NONE', 'FIRST_EPISODE_PER_GROUP', 'EVERY_EPISODE')
    ),
    CONSTRAINT chk_enterprise_alert_route_grouping_behavior CHECK (
        grouping_behavior IN ('SUGGESTED', 'AUTOMATIC')
    ),
    CONSTRAINT chk_enterprise_alert_route_grouping_window_kind CHECK (
        grouping_window_kind IN ('FIXED', 'ROLLING')
    ),
    CONSTRAINT chk_enterprise_alert_route_incident_status CHECK (
        incident_initial_status IN ('TRIAGE', 'ACTIVE')
    ),
    CONSTRAINT chk_enterprise_alert_route_grouping_window CHECK (grouping_window_seconds > 0),
    CONSTRAINT chk_enterprise_alert_route_recovery_grace CHECK (recovery_grace_seconds >= 0)
);

CREATE TABLE enterprise_alert_route_targets (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    route_id INTEGER NOT NULL REFERENCES enterprise_alert_routes(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    escalation_policy_id INTEGER REFERENCES escalation_policies(id) ON DELETE RESTRICT,
    team_id INTEGER REFERENCES organization_teams(id) ON DELETE RESTRICT,
    ownership_source VARCHAR(24),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (route_id, position),
    CONSTRAINT chk_enterprise_alert_route_target_kind CHECK (
        target_kind IN ('ESCALATION_POLICY', 'TEAM', 'OWNERSHIP_DERIVED')
    ),
    CONSTRAINT chk_enterprise_alert_route_target_ownership CHECK (
        ownership_source IS NULL OR ownership_source IN ('SERVICE', 'TEAM', 'CATALOG')
    ),
    CONSTRAINT chk_enterprise_alert_route_target_reference CHECK (
        (target_kind = 'ESCALATION_POLICY' AND escalation_policy_id IS NOT NULL)
        OR (target_kind = 'TEAM' AND team_id IS NOT NULL)
        OR (target_kind = 'OWNERSHIP_DERIVED' AND ownership_source IS NOT NULL)
    )
);

CREATE TABLE enterprise_alert_route_commands (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    command_key VARCHAR(160) NOT NULL,
    command_type VARCHAR(24) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    result_route_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, command_key),
    CONSTRAINT chk_enterprise_alert_route_command_type CHECK (
        command_type IN ('CREATE', 'UPDATE', 'DELETE', 'REORDER')
    )
);

CREATE TABLE enterprise_alert_route_revisions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    route_id INTEGER REFERENCES enterprise_alert_routes(id) ON DELETE SET NULL,
    route_resource_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    command_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, route_resource_id, revision),
    CONSTRAINT chk_enterprise_alert_route_revision_number CHECK (revision > 0),
    CONSTRAINT chk_enterprise_alert_route_change_type CHECK (
        change_type IN ('CREATED', 'UPDATED', 'REORDERED', 'DELETED')
    )
);

CREATE INDEX idx_enterprise_alert_route_revisions_history
    ON enterprise_alert_route_revisions(organization_id, route_resource_id, revision DESC);

CREATE INDEX idx_enterprise_alert_route_revisions_command
    ON enterprise_alert_route_revisions(organization_id, command_key);
