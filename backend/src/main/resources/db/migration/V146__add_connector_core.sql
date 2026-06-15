CREATE TABLE IF NOT EXISTS connector_installations (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    credential_type VARCHAR(64) NOT NULL,
    auth_profile_id VARCHAR(64) NOT NULL,
    external_project_id VARCHAR(255),
    external_project_name VARCHAR(255),
    external_project_discovered_at TIMESTAMPTZ,
    auth_permissions_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    status_reason TEXT,
    last_tested_at TIMESTAMPTZ,
    last_test_result VARCHAR(32),
    last_successful_provider_call_at TIMESTAMPTZ,
    last_error TEXT,
    api_secret_ciphertext TEXT,
    api_secret_key_id VARCHAR(64),
    api_secret_last_four VARCHAR(8),
    webhook_token_hash VARCHAR(128),
    webhook_token_prefix VARCHAR(16),
    webhook_token_created_at TIMESTAMPTZ,
    webhook_token_rotated_at TIMESTAMPTZ,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_installations_org_resource_id
    ON connector_installations(organization_id, resource_id);

CREATE INDEX IF NOT EXISTS idx_connector_installations_org_provider
    ON connector_installations(organization_id, provider)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_installations_org_provider_project_active
    ON connector_installations(organization_id, provider, external_project_id)
    WHERE deleted_at IS NULL AND external_project_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS connector_external_resources (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    installation_id INTEGER NOT NULL REFERENCES connector_installations(id) ON DELETE CASCADE,
    external_project_id VARCHAR(255),
    external_resource_type VARCHAR(64) NOT NULL,
    external_resource_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    provider_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_external_resources_org_resource_id
    ON connector_external_resources(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_external_resources_install_resource
    ON connector_external_resources(installation_id, external_resource_type, external_resource_id);

CREATE INDEX IF NOT EXISTS idx_connector_external_resources_org_type
    ON connector_external_resources(organization_id, external_resource_type);

CREATE TABLE IF NOT EXISTS connector_use_bindings (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    installation_id INTEGER NOT NULL REFERENCES connector_installations(id) ON DELETE CASCADE,
    external_project_id VARCHAR(255),
    external_resource_type VARCHAR(64) NOT NULL,
    external_resource_id VARCHAR(255) NOT NULL,
    local_resource_type VARCHAR(64) NOT NULL,
    local_resource_id UUID NOT NULL,
    local_resource_numeric_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    effective_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_to TIMESTAMPTZ,
    binding_version INTEGER NOT NULL DEFAULT 1,
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    updated_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_use_bindings_org_resource_id
    ON connector_use_bindings(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_use_bindings_active_install_external
    ON connector_use_bindings(installation_id, external_resource_type, external_resource_id)
    WHERE effective_to IS NULL AND status = 'active';

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_use_bindings_active_org_external
    ON connector_use_bindings(organization_id, external_resource_type, external_resource_id)
    WHERE effective_to IS NULL AND status = 'active';

CREATE INDEX IF NOT EXISTS idx_connector_use_bindings_local_resource
    ON connector_use_bindings(organization_id, local_resource_type, local_resource_id)
    WHERE effective_to IS NULL AND status = 'active';

CREATE TABLE IF NOT EXISTS connector_inbound_events_raw (
    id BIGSERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    installation_id INTEGER NOT NULL REFERENCES connector_installations(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    request_headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw_payload TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    provider_event_timestamp_ms BIGINT,
    event_type VARCHAR(128),
    environment VARCHAR(64),
    external_project_id VARCHAR(255),
    external_resource_id VARCHAR(255),
    auth_token_prefix VARCHAR(16)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_inbound_events_raw_org_resource_id
    ON connector_inbound_events_raw(organization_id, resource_id);

CREATE INDEX IF NOT EXISTS idx_connector_inbound_events_raw_install_received
    ON connector_inbound_events_raw(installation_id, received_at DESC);

CREATE TABLE IF NOT EXISTS connector_event_receipts (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    installation_id INTEGER NOT NULL REFERENCES connector_installations(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    raw_event_id BIGINT NOT NULL REFERENCES connector_inbound_events_raw(id) ON DELETE CASCADE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    provider_event_timestamp_ms BIGINT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL DEFAULT 'received',
    worker_claimed_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_event_receipts_org_resource_id
    ON connector_event_receipts(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_connector_event_receipts_install_provider_event
    ON connector_event_receipts(installation_id, provider_event_id);

CREATE INDEX IF NOT EXISTS idx_connector_event_receipts_install_state
    ON connector_event_receipts(installation_id, state, updated_at DESC);
