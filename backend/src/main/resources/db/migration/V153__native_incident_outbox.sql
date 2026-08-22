-- Durable domain events for enterprise-native incidents.

CREATE TABLE native_incident_outbox_events (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    aggregate_version INTEGER NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    leased_at TIMESTAMP WITH TIME ZONE,
    lease_owner VARCHAR(120),
    last_error TEXT,
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, resource_id),
    UNIQUE (organization_id, idempotency_key),
    CONSTRAINT chk_native_incident_outbox_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD_LETTER')
    ),
    CONSTRAINT chk_native_incident_outbox_version CHECK (aggregate_version > 0),
    CONSTRAINT chk_native_incident_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_native_incident_outbox_ready
    ON native_incident_outbox_events(status, available_at, id);
CREATE INDEX idx_native_incident_outbox_aggregate
    ON native_incident_outbox_events(incident_id, aggregate_version, id);

CREATE TABLE native_incident_outbox_deliveries (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    event_id INTEGER NOT NULL REFERENCES native_incident_outbox_events(id) ON DELETE CASCADE,
    consumer_name VARCHAR(120) NOT NULL,
    delivery_key VARCHAR(384) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    leased_at TIMESTAMP WITH TIME ZONE,
    lease_owner VARCHAR(120),
    last_error TEXT,
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (event_id, consumer_name),
    UNIQUE (delivery_key),
    CONSTRAINT chk_native_incident_delivery_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'DEAD_LETTER')
    ),
    CONSTRAINT chk_native_incident_delivery_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_native_incident_deliveries_ready
    ON native_incident_outbox_deliveries(status, available_at, id);
