CREATE TABLE custom_data_sources (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    source_type     VARCHAR(50) NOT NULL,    -- 'postgresql', 'prometheus'
    host            VARCHAR(512) NOT NULL,
    port            INT,
    database_name   VARCHAR(255),
    encrypted_credentials JSONB NOT NULL,     -- AES-256-GCM encrypted JSON { "username": "...", "password": "..." }
    extra_config    JSONB NOT NULL DEFAULT '{}', -- TLS, timeouts, etc.
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_by      BIGINT NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_custom_data_sources_org_id ON custom_data_sources(org_id);
CREATE UNIQUE INDEX idx_custom_data_sources_org_name ON custom_data_sources(org_id, name);
