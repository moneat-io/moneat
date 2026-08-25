-- Moneat - observability platform
-- Copyright (C) 2026 Moneat
-- Licensed under the GNU Affero General Public License, version 3.

CREATE TABLE slack_outbound_deliveries (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    team_id VARCHAR(255),
    channel_id VARCHAR(255),
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(384) NOT NULL,
    payload TEXT NOT NULL,
    desired_version INTEGER NOT NULL DEFAULT 1,
    delivered_version INTEGER,
    provider_message_id VARCHAR(255),
    provider_message_ts VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    rate_limit_reset_at TIMESTAMPTZ,
    leased_at TIMESTAMPTZ,
    lease_owner VARCHAR(120),
    last_error TEXT,
    superseded_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_slack_outbound_resource UNIQUE (organization_id, resource_id),
    CONSTRAINT uq_slack_outbound_idempotency UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX idx_slack_outbound_ready
    ON slack_outbound_deliveries(status, available_at, id);
CREATE INDEX idx_slack_outbound_workspace
    ON slack_outbound_deliveries(organization_id, team_id, status);
