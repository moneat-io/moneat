-- File blobs for chunked uploads (Sentry-compatible chunk upload API)
CREATE TABLE IF NOT EXISTS file_blobs (
    id SERIAL PRIMARY KEY,
    checksum VARCHAR(40) NOT NULL,
    size BIGINT NOT NULL DEFAULT 0,
    storage_path VARCHAR(500) NOT NULL,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_file_blobs_checksum ON file_blobs(checksum);

-- Artifact bundles for assembled source maps and debug files
CREATE TABLE IF NOT EXISTS artifact_bundles (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id),
    checksum VARCHAR(40) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'created',
    detail TEXT,
    version VARCHAR(255),
    dist VARCHAR(255),
    storage_path VARCHAR(500),
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_artifact_bundles_org_checksum ON artifact_bundles(organization_id, checksum);
