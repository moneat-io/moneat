-- Durable enterprise Alert Route grouping and correlation decisions.

CREATE TABLE enterprise_alert_groups (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    route_id INTEGER REFERENCES enterprise_alert_routes(id) ON DELETE SET NULL,
    route_resource_id UUID NOT NULL,
    route_revision INTEGER NOT NULL,
    identity_hash VARCHAR(64) NOT NULL,
    grouping_tuple JSONB NOT NULL DEFAULT '{}'::jsonb,
    singleton_episode_id INTEGER REFERENCES alert_episodes(id) ON DELETE RESTRICT,
    grouping_behavior VARCHAR(24) NOT NULL,
    window_kind VARCHAR(16) NOT NULL,
    window_seconds INTEGER NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_alert_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closes_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    version INTEGER NOT NULL DEFAULT 1,
    incident_id INTEGER REFERENCES on_call_incidents(id) ON DELETE RESTRICT,
    candidate_incident_id INTEGER REFERENCES on_call_incidents(id) ON DELETE SET NULL,
    route_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    incident_template_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    paging_mode VARCHAR(32) NOT NULL,
    paging_state VARCHAR(24) NOT NULL DEFAULT 'READY',
    paging_command_key VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (organization_id, resource_id),
    CONSTRAINT chk_enterprise_alert_group_revision CHECK (route_revision > 0),
    CONSTRAINT chk_enterprise_alert_group_version CHECK (version > 0),
    CONSTRAINT chk_enterprise_alert_group_window CHECK (window_seconds > 0),
    CONSTRAINT chk_enterprise_alert_group_state CHECK (state IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_enterprise_alert_group_behavior CHECK (grouping_behavior IN ('SUGGESTED', 'AUTOMATIC')),
    CONSTRAINT chk_enterprise_alert_group_window_kind CHECK (window_kind IN ('FIXED', 'ROLLING')),
    CONSTRAINT chk_enterprise_alert_group_paging_state CHECK (
        paging_state IN ('READY', 'CLAIMED', 'DELIVERED', 'FAILED', 'NOT_REQUIRED')
    ),
    CONSTRAINT chk_enterprise_alert_group_paging_mode CHECK (
        paging_mode IN ('NONE', 'FIRST_EPISODE_PER_GROUP', 'EVERY_EPISODE')
    )
);

CREATE UNIQUE INDEX uq_enterprise_alert_groups_open_identity
    ON enterprise_alert_groups(organization_id, route_resource_id, identity_hash)
    WHERE state = 'OPEN';
CREATE INDEX idx_enterprise_alert_groups_active
    ON enterprise_alert_groups(organization_id, state, closes_at);
CREATE TABLE enterprise_alert_group_members (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    group_id INTEGER NOT NULL REFERENCES enterprise_alert_groups(id) ON DELETE CASCADE,
    alert_episode_id INTEGER NOT NULL REFERENCES alert_episodes(id) ON DELETE RESTRICT,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INTEGER NOT NULL DEFAULT 1,
    paging_state VARCHAR(24) NOT NULL DEFAULT 'READY',
    paging_command_key VARCHAR(200),
    first_joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, alert_episode_id),
    CONSTRAINT chk_enterprise_alert_group_member_state CHECK (state IN ('ACTIVE', 'REMOVED', 'UNRELATED')),
    CONSTRAINT chk_enterprise_alert_group_member_paging_state CHECK (
        paging_state IN ('READY', 'CLAIMED', 'DELIVERED', 'FAILED', 'NOT_REQUIRED')
    ),
    CONSTRAINT chk_enterprise_alert_group_member_version CHECK (version > 0)
);

CREATE INDEX idx_enterprise_alert_group_members_group
    ON enterprise_alert_group_members(organization_id, group_id, state);

CREATE TABLE enterprise_alert_group_decisions (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    group_id INTEGER NOT NULL REFERENCES enterprise_alert_groups(id) ON DELETE CASCADE,
    decision_type VARCHAR(32) NOT NULL,
    alert_episode_id INTEGER REFERENCES alert_episodes(id) ON DELETE RESTRICT,
    incident_id INTEGER REFERENCES on_call_incidents(id) ON DELETE SET NULL,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    command_key VARCHAR(160) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, command_key),
    CONSTRAINT chk_enterprise_alert_group_decision_type CHECK (
        decision_type IN ('GROUP_CREATED', 'SUGGESTED', 'MARKED_UNRELATED', 'REMOVED',
                          'ATTACHED', 'AUTOMATICALLY_ATTACHED', 'TRIAGE_CREATED')
    )
);

CREATE INDEX idx_enterprise_alert_group_decisions_history
    ON enterprise_alert_group_decisions(organization_id, group_id, created_at, id);

CREATE TABLE enterprise_alert_group_escalations (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    group_id INTEGER NOT NULL REFERENCES enterprise_alert_groups(id) ON DELETE CASCADE,
    escalation_key VARCHAR(200) NOT NULL,
    escalation_policy_id INTEGER NOT NULL REFERENCES escalation_policies(id) ON DELETE RESTRICT,
    on_call_alert_id INTEGER REFERENCES on_call_alerts(id) ON DELETE SET NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (organization_id, resource_id),
    UNIQUE (group_id, escalation_key),
    CONSTRAINT chk_enterprise_alert_group_escalation_state CHECK (
        state IN ('PENDING', 'TRIGGERED', 'RESOLVED', 'CANCELLED', 'FAILED')
    )
);

CREATE TABLE enterprise_alert_group_commands (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    group_id INTEGER REFERENCES enterprise_alert_groups(id) ON DELETE SET NULL,
    command_key VARCHAR(160) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    result_version INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (organization_id, command_key),
    CONSTRAINT chk_enterprise_alert_group_command_type CHECK (
        command_type IN ('MARK_UNRELATED', 'REMOVE_EPISODE', 'ATTACH_INCIDENT', 'CREATE_TRIAGE')
    )
);
