-- V31: Priority levels and business hours configuration

-- Alert priority mappings (maps AlertSeverity to priority levels)
CREATE TABLE alert_priorities (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    severity VARCHAR(20) NOT NULL,
    priority_level VARCHAR(10) NOT NULL,
    is_pageable BOOLEAN NOT NULL DEFAULT true,
    label VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(organization_id, severity)
);

CREATE INDEX idx_alert_priorities_org ON alert_priorities(organization_id);

-- Business hours configuration
CREATE TABLE business_hours (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    timezone VARCHAR(100) NOT NULL DEFAULT 'UTC',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Business hours time windows (multiple per organization)
CREATE TABLE business_hours_windows (
    id SERIAL PRIMARY KEY,
    business_hours_id INTEGER NOT NULL REFERENCES business_hours(id) ON DELETE CASCADE,
    day_of_week INTEGER NOT NULL, -- 0=Sunday, 6=Saturday
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_day_of_week CHECK (day_of_week BETWEEN 0 AND 6)
);

CREATE INDEX idx_business_hours_windows_hours ON business_hours_windows(business_hours_id);

-- Insert default priority mappings for existing organizations
INSERT INTO alert_priorities (organization_id, severity, priority_level, is_pageable, label, description)
SELECT 
    id,
    'CRITICAL',
    'P0',
    true,
    'Critical - Page 24/7',
    'Critical incidents that require immediate response at any time'
FROM organizations
UNION ALL
SELECT 
    id,
    'HIGH',
    'P1',
    true,
    'High - Page 24/7',
    'High priority incidents requiring rapid response'
FROM organizations
UNION ALL
SELECT 
    id,
    'MEDIUM',
    'P2',
    true,
    'Medium - Page 24/7',
    'Medium priority incidents that should be addressed promptly'
FROM organizations
UNION ALL
SELECT 
    id,
    'LOW',
    'P3',
    false,
    'Low - Business Hours Only',
    'Low priority issues that can wait for business hours'
FROM organizations;

-- Insert default business hours for existing organizations (Mon-Fri, 9-5)
INSERT INTO business_hours (organization_id, timezone, enabled)
SELECT id, 'UTC', true FROM organizations;

INSERT INTO business_hours_windows (business_hours_id, day_of_week, start_time, end_time)
SELECT 
    bh.id,
    dow.day,
    '09:00:00'::TIME,
    '17:00:00'::TIME
FROM business_hours bh
CROSS JOIN (
    SELECT 1 AS day UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
) dow;
