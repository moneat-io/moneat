-- PostgreSQL initialization script

-- Users and authentication
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    email_verified BOOLEAN DEFAULT false,
    email_verification_token VARCHAR(255),
    email_verification_expires_at BIGINT,
    password_reset_token VARCHAR(255),
    password_reset_expires_at BIGINT,
    onboarding_completed BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_verification_token ON users(email_verification_token);
CREATE INDEX idx_users_reset_token ON users(password_reset_token);

-- User legal acceptance audit trail
CREATE TABLE IF NOT EXISTS user_legal_acceptances (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_type VARCHAR(20) NOT NULL,
    document_version VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(64),
    user_agent TEXT,
    UNIQUE(user_id, document_type, document_version)
);

CREATE INDEX idx_user_legal_acceptances_user ON user_legal_acceptances(user_id);
CREATE INDEX idx_user_legal_acceptances_doc_version ON user_legal_acceptances(document_type, document_version);

-- Organizations
CREATE TABLE IF NOT EXISTS organizations (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) UNIQUE NOT NULL,
    company_size VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_organizations_slug ON organizations(slug);

-- Organization memberships
CREATE TABLE IF NOT EXISTS memberships (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL DEFAULT 'member',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, organization_id)
);

CREATE INDEX idx_memberships_user ON memberships(user_id);
CREATE INDEX idx_memberships_org ON memberships(organization_id);

-- Projects
CREATE TABLE IF NOT EXISTS projects (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    framework VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(organization_id, slug)
);

CREATE INDEX idx_projects_org ON projects(organization_id);

-- Project keys (DSN keys)
CREATE TABLE IF NOT EXISTS project_keys (
    id SERIAL PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    public_key VARCHAR(255) UNIQUE NOT NULL,
    secret_key VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    platform_target VARCHAR(50),
    is_active BOOLEAN DEFAULT true,
    rate_limit INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_project_keys_public ON project_keys(public_key);
CREATE INDEX idx_project_keys_project ON project_keys(project_id);
CREATE UNIQUE INDEX idx_project_keys_platform_target ON project_keys(project_id, platform_target) WHERE platform_target IS NOT NULL;

-- Optional metadata for declared log sources
CREATE TABLE IF NOT EXISTS log_sources (
    id SERIAL PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_type VARCHAR(50) NOT NULL,
    source_name VARCHAR(255),
    config JSONB,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_log_sources_project ON log_sources(project_id);
CREATE INDEX IF NOT EXISTS idx_log_sources_active ON log_sources(project_id, is_active);

-- Releases
CREATE TABLE IF NOT EXISTS releases (
    id SERIAL PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    version VARCHAR(255) NOT NULL,
    ref VARCHAR(255),
    created_at BIGINT,
    first_seen BIGINT,
    last_seen BIGINT,
    event_count BIGINT DEFAULT 0,
    is_auto_detected BOOLEAN DEFAULT false,
    UNIQUE(project_id, version)
);

CREATE INDEX idx_releases_project ON releases(project_id);

-- Source map files
CREATE TABLE IF NOT EXISTS release_files (
    id SERIAL PRIMARY KEY,
    release_id INTEGER NOT NULL REFERENCES releases(id) ON DELETE CASCADE,
    name VARCHAR(500) NOT NULL,
    file_path VARCHAR(1000),
    storage_path VARCHAR(1000),
    file_type VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_release_files_release ON release_files(release_id);

-- Subscriptions (Stripe)
CREATE TABLE IF NOT EXISTS subscriptions (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stripe_subscription_id VARCHAR(255) UNIQUE,
    stripe_customer_id VARCHAR(255),
    plan VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    current_period_start TIMESTAMP,
    current_period_end TIMESTAMP,
    pricing_tier_config_id INTEGER,
    payg_budget_cents INT NOT NULL DEFAULT 0,
    payg_used_units BIGINT NOT NULL DEFAULT 0,
    payg_used_micros BIGINT NOT NULL DEFAULT 0,
    pending_meter_units BIGINT NOT NULL DEFAULT 0,
    stripe_base_item_id VARCHAR(255),
    stripe_overage_item_id VARCHAR(255),
    billing_grace_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscriptions_org ON subscriptions(organization_id);
CREATE INDEX idx_subscriptions_stripe ON subscriptions(stripe_subscription_id);

-- Versioned billing tier configuration
CREATE TABLE IF NOT EXISTS pricing_tier_configs (
    id SERIAL PRIMARY KEY,
    tier_name VARCHAR(50) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    monthly_unit_limit BIGINT NOT NULL,
    monthly_error_limit BIGINT NOT NULL DEFAULT 0,
    monthly_transaction_limit BIGINT NOT NULL DEFAULT 0,
    monthly_replay_limit BIGINT NOT NULL DEFAULT 0,
    monthly_feedback_limit BIGINT NOT NULL DEFAULT 0,
    retention_days INT NOT NULL,
    max_projects INT,
    max_systems INT NOT NULL,
    monitor_interval_seconds INT NOT NULL,
    monthly_price_cents INT NOT NULL,
    payg_enabled BOOLEAN NOT NULL DEFAULT false,
    payg_rate_micros_per_unit BIGINT NOT NULL DEFAULT 0,
    stripe_base_price_id VARCHAR(255),
    stripe_overage_price_id VARCHAR(255),
    is_current BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tier_name, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pricing_tier_current_per_name
    ON pricing_tier_configs (tier_name)
    WHERE is_current = true;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_subscriptions_pricing_tier'
    ) THEN
        ALTER TABLE subscriptions
            ADD CONSTRAINT fk_subscriptions_pricing_tier
            FOREIGN KEY (pricing_tier_config_id) REFERENCES pricing_tier_configs(id);
    END IF;
END$$;

-- Usage records
CREATE TABLE IF NOT EXISTS usage_records (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id INTEGER REFERENCES projects(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL DEFAULT 'error',
    event_count INTEGER NOT NULL DEFAULT 0,
    bytes_ingested BIGINT NOT NULL DEFAULT 0,
    date DATE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(organization_id, project_id, date, event_type)
);

CREATE INDEX idx_usage_org_date ON usage_records(organization_id, date);

CREATE TABLE IF NOT EXISTS org_usage_counters (
    id SERIAL PRIMARY KEY,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    used_units BIGINT NOT NULL DEFAULT 0,
    used_errors BIGINT NOT NULL DEFAULT 0,
    used_transactions BIGINT NOT NULL DEFAULT 0,
    used_replays BIGINT NOT NULL DEFAULT 0,
    used_feedback BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, period_start)
);

CREATE TABLE IF NOT EXISTS quota_notifications_sent (
    id SERIAL PRIMARY KEY,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, period_start, notification_type)
);

CREATE TABLE IF NOT EXISTS stripe_webhook_events (
    id SERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO pricing_tier_configs (
    tier_name,
    version,
    monthly_unit_limit,
    monthly_error_limit,
    monthly_transaction_limit,
    monthly_replay_limit,
    monthly_feedback_limit,
    retention_days,
    max_projects,
    max_systems,
    monitor_interval_seconds,
    monthly_price_cents,
    payg_enabled,
    payg_rate_micros_per_unit,
    stripe_base_price_id,
    stripe_overage_price_id,
    is_current
) VALUES
    ('FREE', 1, 10000, 10000, 0, 0, 0, 30, 1, 1, 60, 0, false, 0, NULL, NULL, true),
    ('PRO', 1, 500000, 500000, 0, 0, 0, 90, NULL, 5, 15, 1900, true, 10, NULL, NULL, true),
    ('TEAM', 1, 5000000, 5000000, 0, 0, 0, 90, NULL, 25, 10, 4900, true, 10, NULL, NULL, true)
ON CONFLICT (tier_name, version) DO NOTHING;
