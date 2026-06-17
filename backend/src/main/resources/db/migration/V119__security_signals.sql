-- Security signals: the deduplicated, scored, triageable finding model — the single triage surface.
-- State lives here in Postgres; matched evidence stays in ClickHouse, referenced by a query descriptor.

CREATE TABLE security_signals (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source VARCHAR(32) NOT NULL,
    rule_id VARCHAR(255) NOT NULL,
    rule_name VARCHAR(255) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'open',
    archive_reason VARCHAR(16),
    dedup_key TEXT NOT NULL,
    entities JSONB NOT NULL DEFAULT '{}'::JSONB,
    sample_count INTEGER NOT NULL DEFAULT 1,
    assignee_user_id INTEGER,
    tags JSONB NOT NULL DEFAULT '[]'::JSONB,
    first_seen TIMESTAMPTZ NOT NULL,
    last_seen TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Primary triage list ordering: scope by org + status + severity, most-recent first.
CREATE INDEX idx_security_signals_triage
    ON security_signals (organization_id, status, severity, last_seen);

-- Open-signal dedup: while a signal is open, repeats fold into it. Once archived, a new
-- occurrence forms a fresh signal — so the uniqueness only applies to open rows.
CREATE UNIQUE INDEX idx_security_signals_open_dedup
    ON security_signals (organization_id, rule_id, dedup_key)
    WHERE status = 'open';

CREATE TABLE security_signal_evidence (
    id SERIAL PRIMARY KEY,
    signal_id INTEGER NOT NULL REFERENCES security_signals(id) ON DELETE CASCADE,
    evidence_type VARCHAR(32) NOT NULL,
    reference TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_security_signal_evidence_signal
    ON security_signal_evidence (signal_id, id);

CREATE TABLE security_signal_audit (
    id SERIAL PRIMARY KEY,
    signal_id INTEGER NOT NULL REFERENCES security_signals(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    actor_user_id INTEGER,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    reason VARCHAR(16),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_security_signal_audit_signal
    ON security_signal_audit (signal_id, id);
