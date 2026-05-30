-- Human approval gates for Enterprise workflows.

CREATE TABLE IF NOT EXISTS workflow_approvals (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workflow_id INTEGER NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    run_id INTEGER NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    node_id VARCHAR(120) NOT NULL,
    message TEXT NOT NULL,
    approver_role VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP,
    responded_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    comment TEXT,
    CONSTRAINT uq_workflow_approvals_run_node UNIQUE (run_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_workflow_approvals_org_status
    ON workflow_approvals (organization_id, status, requested_at DESC);
