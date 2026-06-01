-- Compliance posture state used to detect true passed -> failed regressions.
-- Raw findings remain in ClickHouse; this compact Postgres row tracks the latest
-- state for each control/resource pair so repeated failures do not create noise.

CREATE TABLE security_compliance_finding_states (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    framework VARCHAR(128) NOT NULL,
    rule_id VARCHAR(255) NOT NULL,
    rule_name TEXT NOT NULL DEFAULT '',
    resource_type TEXT NOT NULL DEFAULT '',
    resource_id TEXT NOT NULL DEFAULT '',
    resource_name TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL,
    first_seen TIMESTAMPTZ NOT NULL,
    last_seen TIMESTAMPTZ NOT NULL,
    last_regressed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_security_compliance_state_identity
    ON security_compliance_finding_states (organization_id, framework, rule_id, resource_type, resource_id);

CREATE INDEX idx_security_compliance_state_summary
    ON security_compliance_finding_states (organization_id, framework, status, updated_at);
