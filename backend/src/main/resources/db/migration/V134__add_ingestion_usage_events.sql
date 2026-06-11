CREATE TABLE IF NOT EXISTS ingestion_usage_events (
    id SERIAL PRIMARY KEY,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id INTEGER NULL,
    event_type VARCHAR(50) NOT NULL,
    event_count INTEGER NOT NULL,
    bytes_ingested BIGINT NOT NULL,
    date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ingestion_usage_events_org_date
    ON ingestion_usage_events (organization_id, date);
