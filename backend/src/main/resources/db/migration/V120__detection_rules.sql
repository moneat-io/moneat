-- Detection rules: declarative, never code. The author writes a log-query filter + grouping +
-- window + condition; the RuleQueryCompiler compiles it to a parameterized, org-scoped ClickHouse
-- aggregation, runs it on a cadence, and upserts a security signal per match. OSS core.
--
-- A rule is data. There is NO column here that maps to organization scoping of the compiled query:
-- the engine injects organization_id from its own context, never from a rule field. `organization_id`
-- below scopes ownership of the rule row itself (which org may read/edit it), not the query target.

CREATE TABLE detection_rules (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    source VARCHAR(32) NOT NULL DEFAULT 'logs',
    filter TEXT NOT NULL DEFAULT '',
    group_by JSONB NOT NULL DEFAULT '[]'::JSONB,
    window_seconds INTEGER NOT NULL DEFAULT 300,
    type VARCHAR(32) NOT NULL DEFAULT 'threshold',
    threshold_count INTEGER,
    severity VARCHAR(16) NOT NULL DEFAULT 'medium',
    signal_title TEXT NOT NULL DEFAULT '',
    signal_message TEXT NOT NULL DEFAULT '',
    suppressions JSONB NOT NULL DEFAULT '[]'::JSONB,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    tags JSONB NOT NULL DEFAULT '[]'::JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Scheduler scans enabled rules; list/read is org-scoped.
CREATE INDEX idx_detection_rules_org ON detection_rules (organization_id, enabled);
CREATE INDEX idx_detection_rules_enabled ON detection_rules (enabled);

-- New-value baseline: per (rule, group_key) first-seen state in Postgres (org-keyed, TTL'd by the
-- evaluator's baseline window). Kept here rather than in ClickHouse so the seen-set is small, mutable,
-- and never crosses tenant boundaries. A value absent from the baseline (after warm-up) is a match.
CREATE TABLE detection_baseline_values (
    id SERIAL PRIMARY KEY,
    rule_id INTEGER NOT NULL REFERENCES detection_rules(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    group_key TEXT NOT NULL,
    first_seen TIMESTAMPTZ NOT NULL,
    last_seen TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_detection_baseline_unique
    ON detection_baseline_values (rule_id, group_key);

CREATE INDEX idx_detection_baseline_rule
    ON detection_baseline_values (rule_id, last_seen);
