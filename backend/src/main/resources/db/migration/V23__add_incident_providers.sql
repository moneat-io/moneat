-- V23: Add incident provider integration tables and severity columns

-- Table: incident_provider_configs
-- Stores provider configurations per organization (API keys, provider-specific settings)
CREATE TABLE incident_provider_configs (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    provider_type VARCHAR(50) NOT NULL, -- e.g., 'incident_io', 'pagerduty', 'opsgenie'
    name VARCHAR(255) NOT NULL,
    api_key TEXT NOT NULL, -- encrypted in production
    config_json JSONB NOT NULL DEFAULT '{}', -- provider-specific config (e.g., alert_source_config_id for incident.io)
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(organization_id, provider_type, name)
);

CREATE INDEX idx_incident_provider_configs_org ON incident_provider_configs(organization_id);
CREATE INDEX idx_incident_provider_configs_enabled ON incident_provider_configs(organization_id, enabled);

-- Table: incident_routing_rules
-- Default severity mappings per alert source type
CREATE TABLE incident_routing_rules (
    id SERIAL PRIMARY KEY,
    provider_config_id INTEGER NOT NULL REFERENCES incident_provider_configs(id) ON DELETE CASCADE,
    alert_source VARCHAR(50) NOT NULL, -- 'SYSTEM_ALERT', 'SYSTEM_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT'
    alert_type VARCHAR(100), -- optional, for fine-grained routing (e.g., specific metric types)
    incident_severity VARCHAR(20) NOT NULL, -- 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider_config_id, alert_source, alert_type)
);

CREATE INDEX idx_incident_routing_rules_provider ON incident_routing_rules(provider_config_id);

-- Table: incident_event_log
-- Audit trail of all incident events dispatched
CREATE TABLE incident_event_log (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    provider_config_id INTEGER NOT NULL REFERENCES incident_provider_configs(id) ON DELETE CASCADE,
    alert_source VARCHAR(50) NOT NULL,
    deduplication_key VARCHAR(255) NOT NULL,
    incident_severity VARCHAR(20) NOT NULL,
    incident_status VARCHAR(20) NOT NULL, -- 'FIRING', 'RESOLVED'
    title TEXT NOT NULL,
    description TEXT,
    provider_incident_id TEXT, -- incident ID returned by provider
    success BOOLEAN NOT NULL,
    error_message TEXT,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_incident_event_log_org ON incident_event_log(organization_id, created_at DESC);
CREATE INDEX idx_incident_event_log_provider ON incident_event_log(provider_config_id, created_at DESC);
CREATE INDEX idx_incident_event_log_dedup ON incident_event_log(deduplication_key);

-- Add incident_severity column to system_alerts
ALTER TABLE system_alerts ADD COLUMN incident_severity VARCHAR(20);

-- Add incident_severity column to organization_alert_templates
ALTER TABLE organization_alert_templates ADD COLUMN incident_severity VARCHAR(20);

-- Add incident_severity column to uptime_monitors
ALTER TABLE uptime_monitors ADD COLUMN incident_severity VARCHAR(20);
