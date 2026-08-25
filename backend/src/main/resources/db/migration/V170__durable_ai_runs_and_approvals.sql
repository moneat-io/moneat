-- Durable execution state for enterprise AI conversations and tool approvals.

ALTER TABLE ai_conversations
    ADD COLUMN IF NOT EXISTS project_id BIGINT REFERENCES projects(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS channel VARCHAR(32) NOT NULL DEFAULT 'chat',
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS ai_runs (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id INTEGER NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    project_id BIGINT REFERENCES projects(id) ON DELETE SET NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    current_round INTEGER NOT NULL DEFAULT 0,
    provider VARCHAR(64),
    model VARCHAR(255),
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cost_usd NUMERIC(18, 8) NOT NULL DEFAULT 0,
    cost_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_content TEXT,
    error_code VARCHAR(64),
    error_message TEXT,
    cancellation_requested_at TIMESTAMPTZ,
    cancellation_requested_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_ai_runs_status CHECK (
        status IN ('pending', 'running', 'waiting_for_approval', 'completed', 'failed', 'cancelled')
    ),
    CONSTRAINT uq_ai_runs_actor_idempotency UNIQUE (organization_id, user_id, idempotency_key),
    CONSTRAINT uq_ai_runs_org_resource UNIQUE (organization_id, resource_id)
);

ALTER TABLE ai_messages
    ADD COLUMN IF NOT EXISTS run_id BIGINT REFERENCES ai_runs(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS tool_call_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS tool_calls JSONB,
    ADD COLUMN IF NOT EXISTS sequence_number BIGINT;

CREATE TABLE IF NOT EXISTS ai_tool_calls (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    run_id BIGINT NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    round INTEGER NOT NULL,
    provider_call_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    arguments JSONB,
    arguments_valid BOOLEAN NOT NULL DEFAULT TRUE,
    read_only BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'proposed',
    effect_idempotency_key VARCHAR(255) NOT NULL,
    result JSONB,
    result_summary TEXT,
    is_error BOOLEAN,
    result_audit_event_id UUID,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ai_tool_calls_status CHECK (
        status IN ('proposed', 'awaiting_approval', 'executing', 'succeeded', 'failed', 'denied', 'expired')
    ),
    CONSTRAINT uq_ai_tool_calls_run_provider_call UNIQUE (run_id, round, provider_call_id),
    CONSTRAINT uq_ai_tool_calls_effect_key UNIQUE (organization_id, effect_idempotency_key),
    CONSTRAINT uq_ai_tool_calls_org_resource UNIQUE (organization_id, resource_id)
);

CREATE TABLE IF NOT EXISTS ai_run_evidence (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    run_id BIGINT NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    evidence_type VARCHAR(64) NOT NULL,
    source VARCHAR(128) NOT NULL,
    source_resource_id VARCHAR(255),
    content JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ai_run_evidence_org_resource UNIQUE (organization_id, resource_id)
);

CREATE TABLE IF NOT EXISTS ai_approvals (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    run_id BIGINT NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    tool_call_id BIGINT NOT NULL UNIQUE REFERENCES ai_tool_calls(id) ON DELETE CASCADE,
    requested_by INTEGER NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    decided_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    incident_resource_id UUID,
    incident_version BIGINT,
    proposed_command JSONB NOT NULL,
    proposal_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    decision_reason TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    result_audit_event_id UUID,
    response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ai_approvals_status CHECK (status IN ('pending', 'approved', 'denied', 'expired')),
    CONSTRAINT uq_ai_approvals_org_resource UNIQUE (organization_id, resource_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_runs_conversation_created
    ON ai_runs (conversation_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_ai_runs_org_status
    ON ai_runs (organization_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_ai_messages_run_sequence
    ON ai_messages (run_id, sequence_number, id);
CREATE INDEX IF NOT EXISTS idx_ai_tool_calls_run_status
    ON ai_tool_calls (run_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_run_evidence_run
    ON ai_run_evidence (run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_approvals_org_pending
    ON ai_approvals (organization_id, status, expires_at);
