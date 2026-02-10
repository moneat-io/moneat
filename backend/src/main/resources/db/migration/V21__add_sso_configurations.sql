-- SSO provider configurations per organization
CREATE TABLE sso_configurations (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id),
    provider_type VARCHAR(10) NOT NULL CHECK (provider_type IN ('saml', 'oidc')),
    is_enabled BOOLEAN NOT NULL DEFAULT false,

    -- SAML fields
    idp_entity_id VARCHAR(512),
    idp_sso_url VARCHAR(1024),
    idp_certificate TEXT,
    sp_entity_id VARCHAR(512),

    -- OIDC fields
    oidc_issuer_url VARCHAR(1024),
    oidc_client_id VARCHAR(256),
    oidc_client_secret VARCHAR(512),

    -- Shared
    email_domain VARCHAR(256),           -- e.g. "acme.com" for domain-based SSO routing
    require_sso BOOLEAN DEFAULT false,   -- force all org members to use SSO
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),

    UNIQUE(organization_id)
);

-- Link external SSO identities to internal users
CREATE TABLE user_sso_links (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    sso_configuration_id INTEGER NOT NULL REFERENCES sso_configurations(id),
    external_id VARCHAR(512) NOT NULL,   -- NameID (SAML) or sub claim (OIDC)
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(sso_configuration_id, external_id)
);

CREATE INDEX idx_sso_config_email_domain ON sso_configurations(email_domain);
CREATE INDEX idx_sso_config_org_id ON sso_configurations(organization_id);
CREATE INDEX idx_user_sso_links_user ON user_sso_links(user_id);
