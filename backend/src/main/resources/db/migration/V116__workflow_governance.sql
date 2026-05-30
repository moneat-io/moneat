-- Governance for workflows: audit trail and per-execution usage accounting.
-- Audit events capture lifecycle and run transitions for the workflows surface.
-- Usage events count completed/refused executions per UTC month with a hard
-- idempotency guarantee via UNIQUE (run_id, outcome) so replays never double-count.

CREATE TABLE IF NOT EXISTS workflow_audit_events (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workflow_id INTEGER REFERENCES workflows(id) ON DELETE CASCADE,
    run_id INTEGER,
    action VARCHAR(48) NOT NULL,
    actor_user_id INTEGER,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflow_audit_events_org_created
    ON workflow_audit_events (organization_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_workflow_audit_events_workflow_created
    ON workflow_audit_events (workflow_id, created_at DESC);

CREATE TABLE IF NOT EXISTS workflow_usage_events (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workflow_id INTEGER,
    run_id INTEGER NOT NULL,
    period VARCHAR(7) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, outcome)
);

CREATE INDEX IF NOT EXISTS idx_workflow_usage_events_org_period
    ON workflow_usage_events (organization_id, period);
