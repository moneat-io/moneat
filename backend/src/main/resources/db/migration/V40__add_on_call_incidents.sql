CREATE TABLE on_call_incidents (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    severity VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    declared_by INTEGER NOT NULL REFERENCES users(id),
    declared_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_by INTEGER REFERENCES users(id),
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE on_call_incident_alerts (
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    alert_id INTEGER NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    PRIMARY KEY (incident_id, alert_id)
);

ALTER TABLE incidents ADD COLUMN incident_id INTEGER REFERENCES on_call_incidents(id) ON DELETE SET NULL;
