CREATE TABLE IF NOT EXISTS synthetic_tests (
    id UUID PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    test_type VARCHAR(20) NOT NULL DEFAULT 'api',
    active BOOLEAN NOT NULL DEFAULT true,
    interval_seconds INTEGER NOT NULL DEFAULT 300,
    timeout_seconds INTEGER NOT NULL DEFAULT 30,
    url TEXT,
    method VARCHAR(10) NOT NULL DEFAULT 'GET',
    headers TEXT,
    body TEXT,
    auth_method VARCHAR(20),
    auth_user VARCHAR(255),
    auth_pass VARCHAR(255),
    assertions TEXT NOT NULL DEFAULT '[]',
    steps TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    last_run_at TIMESTAMPTZ,
    last_status VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_synthetic_tests_org ON synthetic_tests(organization_id);
CREATE INDEX IF NOT EXISTS idx_synthetic_tests_active ON synthetic_tests(organization_id, active);
