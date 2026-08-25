CREATE TABLE slack_inbound_deliveries (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    delivery_key VARCHAR(384) NOT NULL,
    request_type VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    team_id VARCHAR(128),
    enterprise_id VARCHAR(128),
    channel_id VARCHAR(128),
    user_id VARCHAR(128),
    message_ts VARCHAR(64),
    thread_ts VARCHAR(64),
    view_id VARCHAR(256),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    leased_at TIMESTAMPTZ,
    lease_owner VARCHAR(120),
    last_error TEXT,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_slack_inbound_deliveries_resource UNIQUE (resource_id),
    CONSTRAINT uq_slack_inbound_deliveries_key UNIQUE (delivery_key)
);

CREATE INDEX idx_slack_inbound_deliveries_ready
    ON slack_inbound_deliveries (status, available_at, id);

CREATE INDEX idx_slack_inbound_deliveries_context
    ON slack_inbound_deliveries (team_id, channel_id, user_id, created_at);
