-- Server monitoring feature tables

CREATE TABLE systems (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    host VARCHAR(255),
    agent_key_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    last_seen_at TIMESTAMPTZ,
    agent_version VARCHAR(20),
    os VARCHAR(100),
    arch VARCHAR(20),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_systems_org ON systems(organization_id);
CREATE INDEX idx_systems_status ON systems(status);
CREATE INDEX idx_systems_last_seen ON systems(last_seen_at);

CREATE TABLE system_alerts (
    id SERIAL PRIMARY KEY,
    system_id UUID NOT NULL REFERENCES systems(id) ON DELETE CASCADE,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    metric VARCHAR(50) NOT NULL,
    condition VARCHAR(20) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    duration_seconds INT DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    last_triggered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_system_alerts_system ON system_alerts(system_id);
CREATE INDEX idx_system_alerts_enabled ON system_alerts(enabled);
