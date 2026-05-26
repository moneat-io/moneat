CREATE TABLE IF NOT EXISTS feature_flag_environments (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, key)
);

CREATE TABLE IF NOT EXISTS feature_flags (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    value_type VARCHAR(32) NOT NULL,
    client_visible BOOLEAN NOT NULL DEFAULT FALSE,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    archived_at TIMESTAMPTZ,
    UNIQUE (organization_id, key)
);

CREATE TABLE IF NOT EXISTS feature_flag_variants (
    id SERIAL PRIMARY KEY,
    flag_id INTEGER NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    key VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    value_json JSONB NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (flag_id, key)
);

CREATE TABLE IF NOT EXISTS feature_flag_environment_configs (
    id SERIAL PRIMARY KEY,
    flag_id INTEGER NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    environment_id INTEGER NOT NULL REFERENCES feature_flag_environments(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_variant_key VARCHAR(255),
    off_variant_key VARCHAR(255),
    rules_json JSONB NOT NULL DEFAULT '{"rules":[]}'::jsonb,
    version INTEGER NOT NULL DEFAULT 1,
    updated_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (flag_id, environment_id)
);

CREATE TABLE IF NOT EXISTS feature_flag_segments (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    conditions_json JSONB NOT NULL DEFAULT '{"all":[]}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    archived_at TIMESTAMPTZ,
    UNIQUE (organization_id, key)
);

CREATE TABLE IF NOT EXISTS feature_flag_sdk_keys (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    environment_id INTEGER NOT NULL REFERENCES feature_flag_environments(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    key_type VARCHAR(16) NOT NULL,
    key_hash VARCHAR(255) NOT NULL UNIQUE,
    key_prefix VARCHAR(16) NOT NULL,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (key_type IN ('server', 'client'))
);

CREATE TABLE IF NOT EXISTS feature_flag_audit_events (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    environment_id INTEGER REFERENCES feature_flag_environments(id) ON DELETE SET NULL,
    flag_id INTEGER REFERENCES feature_flags(id) ON DELETE SET NULL,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    before_json JSONB,
    after_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feature_flags_org_active
    ON feature_flags (organization_id, archived_at);
CREATE INDEX IF NOT EXISTS idx_feature_flag_configs_environment
    ON feature_flag_environment_configs (environment_id, flag_id);
CREATE INDEX IF NOT EXISTS idx_feature_flag_segments_org_active
    ON feature_flag_segments (organization_id, archived_at);
CREATE INDEX IF NOT EXISTS idx_feature_flag_sdk_keys_hash
    ON feature_flag_sdk_keys (key_hash, key_prefix) WHERE is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_feature_flag_audit_org_created
    ON feature_flag_audit_events (organization_id, created_at DESC);

INSERT INTO feature_flag_environments (organization_id, key, name)
SELECT o.id, defaults.key, defaults.name
FROM organizations o
CROSS JOIN (
    VALUES
        ('production', 'Production'),
        ('staging', 'Staging'),
        ('development', 'Development')
) AS defaults(key, name)
ON CONFLICT (organization_id, key) DO NOTHING;
