-- Add UTM tracking columns to organizations for ROAS analytics
ALTER TABLE organizations ADD COLUMN utm_source VARCHAR(255);
ALTER TABLE organizations ADD COLUMN utm_medium VARCHAR(255);
ALTER TABLE organizations ADD COLUMN utm_campaign VARCHAR(255);
ALTER TABLE organizations ADD COLUMN utm_content VARCHAR(255);
ALTER TABLE organizations ADD COLUMN utm_term VARCHAR(255);

-- Add indexes for common attribution queries
CREATE INDEX idx_organizations_utm_source ON organizations(utm_source);
CREATE INDEX idx_organizations_utm_campaign ON organizations(utm_campaign);
