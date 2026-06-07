ALTER TABLE sso_configurations
    ADD COLUMN email_domain_verified BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN email_domain_verification_token VARCHAR(128),
    ADD COLUMN email_domain_verified_at TIMESTAMP,
    ADD COLUMN email_domain_verified_by INTEGER REFERENCES users(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX idx_sso_config_verified_email_domain
    ON sso_configurations (LOWER(email_domain))
    WHERE email_domain IS NOT NULL AND email_domain_verified = true;

CREATE INDEX idx_sso_config_verified_email_domain_lookup
    ON sso_configurations (LOWER(email_domain), is_enabled, email_domain_verified);
