-- Synthetics feature parity: tags, retry policy, alert config, new test type columns, global variables

ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS tags TEXT DEFAULT '[]';
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS retry_interval_ms INTEGER NOT NULL DEFAULT 300;
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS alert_on_failure BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS alert_channels TEXT DEFAULT '[]';
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS config TEXT;
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS previous_status VARCHAR(20);

-- Global variables for synthetic tests
CREATE TABLE IF NOT EXISTS synthetic_variables (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    value TEXT NOT NULL DEFAULT '',
    is_secret BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(organization_id, name)
);
CREATE INDEX IF NOT EXISTS idx_synthetic_variables_org ON synthetic_variables(organization_id);
