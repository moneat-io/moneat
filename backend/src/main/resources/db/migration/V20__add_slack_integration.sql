-- Add organization integrations table for Slack and future integrations
CREATE TABLE organization_integrations (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    integration_type VARCHAR(50) NOT NULL,
    
    -- OAuth data (for Slack App)
    access_token TEXT,
    bot_user_id VARCHAR(255),
    team_id VARCHAR(255),
    team_name VARCHAR(255),
    
    -- Channel configuration
    channel_id VARCHAR(255),
    channel_name VARCHAR(255),
    
    -- Legacy webhook support (deprecated)
    webhook_url TEXT,
    
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(organization_id, integration_type)
);

CREATE INDEX idx_organization_integrations_org_id ON organization_integrations(organization_id);
CREATE INDEX idx_organization_integrations_enabled ON organization_integrations(enabled);
