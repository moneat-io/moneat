-- Status Pages Feature Schema
-- Custom public status pages with monitor integration, incident management, and custom domains

-- Core status page configuration
CREATE TABLE status_pages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    
    -- Branding
    logo_url TEXT,
    favicon_url TEXT,
    primary_color VARCHAR(7) DEFAULT '#3B82F6', -- hex color
    dark_mode BOOLEAN DEFAULT false,
    
    -- Settings
    show_uptime_history BOOLEAN DEFAULT true,
    history_days INTEGER DEFAULT 90,
    is_public BOOLEAN DEFAULT true,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT slug_format CHECK (slug ~ '^[a-z0-9-]+$')
);

CREATE INDEX idx_status_pages_organization_id ON status_pages(organization_id);
CREATE INDEX idx_status_pages_slug ON status_pages(slug);

-- Monitor associations - which monitors appear on a status page
CREATE TABLE status_page_monitors (
    id SERIAL PRIMARY KEY,
    status_page_id UUID NOT NULL REFERENCES status_pages(id) ON DELETE CASCADE,
    monitor_id UUID NOT NULL REFERENCES uptime_monitors(id) ON DELETE CASCADE,
    display_name VARCHAR(255), -- optional override
    sort_order INTEGER NOT NULL DEFAULT 0,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(status_page_id, monitor_id)
);

CREATE INDEX idx_status_page_monitors_status_page_id ON status_page_monitors(status_page_id);
CREATE INDEX idx_status_page_monitors_monitor_id ON status_page_monitors(monitor_id);

-- Manual incidents and maintenance windows
CREATE TABLE status_page_incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status_page_id UUID NOT NULL REFERENCES status_pages(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- investigating, identified, monitoring, resolved, scheduled, in_progress, completed
    type VARCHAR(50) NOT NULL DEFAULT 'incident', -- incident or maintenance
    impact VARCHAR(50) NOT NULL DEFAULT 'none', -- none, minor, major, critical
    
    -- For maintenance windows
    scheduled_start_at TIMESTAMP,
    scheduled_end_at TIMESTAMP,
    
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT valid_status CHECK (status IN ('investigating', 'identified', 'monitoring', 'resolved', 'scheduled', 'in_progress', 'completed')),
    CONSTRAINT valid_type CHECK (type IN ('incident', 'maintenance')),
    CONSTRAINT valid_impact CHECK (impact IN ('none', 'minor', 'major', 'critical'))
);

CREATE INDEX idx_status_page_incidents_status_page_id ON status_page_incidents(status_page_id);
CREATE INDEX idx_status_page_incidents_status ON status_page_incidents(status);
CREATE INDEX idx_status_page_incidents_created_at ON status_page_incidents(created_at DESC);

-- Incident timeline updates
CREATE TABLE status_page_incident_updates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES status_page_incidents(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT valid_update_status CHECK (status IN ('investigating', 'identified', 'monitoring', 'resolved', 'scheduled', 'in_progress', 'completed'))
);

CREATE INDEX idx_status_page_incident_updates_incident_id ON status_page_incident_updates(incident_id);
CREATE INDEX idx_status_page_incident_updates_created_at ON status_page_incident_updates(created_at DESC);

-- Custom domain configuration
CREATE TABLE status_page_custom_domains (
    id SERIAL PRIMARY KEY,
    status_page_id UUID NOT NULL REFERENCES status_pages(id) ON DELETE CASCADE,
    domain VARCHAR(255) NOT NULL UNIQUE,
    verification_token VARCHAR(64) NOT NULL,
    verified BOOLEAN DEFAULT false,
    verified_at TIMESTAMP,
    ssl_provisioned BOOLEAN DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT domain_format CHECK (domain ~ '^[a-z0-9][a-z0-9.-]+[a-z0-9]$')
);

CREATE INDEX idx_status_page_custom_domains_status_page_id ON status_page_custom_domains(status_page_id);
CREATE INDEX idx_status_page_custom_domains_domain ON status_page_custom_domains(domain);
CREATE INDEX idx_status_page_custom_domains_verified ON status_page_custom_domains(verified);
