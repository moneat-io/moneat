-- Synthetic monitoring locations: managed (platform-global) + private (per-org worker).
-- Tests reference locations by their stable `code`; the entity's external id is a resource UUID.

CREATE TABLE IF NOT EXISTS synthetic_locations (
    id UUID PRIMARY KEY,
    organization_id INTEGER REFERENCES organizations(id) ON DELETE CASCADE, -- NULL = managed/global
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    region VARCHAR(128) NOT NULL DEFAULT '',
    location_type VARCHAR(16) NOT NULL DEFAULT 'managed', -- 'managed' | 'private'
    active BOOLEAN NOT NULL DEFAULT true,
    key_hash VARCHAR(255), -- hashed private-location probe key; NULL for managed
    worker_count INTEGER NOT NULL DEFAULT 0,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Managed codes are globally unique; private codes are unique per organization.
CREATE UNIQUE INDEX IF NOT EXISTS idx_synthetic_locations_managed_code
    ON synthetic_locations(code) WHERE organization_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_synthetic_locations_private_code
    ON synthetic_locations(organization_id, code) WHERE organization_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_synthetic_locations_org ON synthetic_locations(organization_id);

-- Seed the managed location fleet (organization_id NULL = visible to all orgs).
INSERT INTO synthetic_locations (id, organization_id, code, name, region, location_type, active) VALUES
    (gen_random_uuid(), NULL, 'aws-us-east-1', 'US East', 'N. Virginia', 'managed', true),
    (gen_random_uuid(), NULL, 'aws-us-west-2', 'US West', 'Oregon', 'managed', true),
    (gen_random_uuid(), NULL, 'aws-eu-central-1', 'EU · Frankfurt', 'Germany', 'managed', true),
    (gen_random_uuid(), NULL, 'aws-eu-west-1', 'EU · Ireland', 'Dublin', 'managed', true),
    (gen_random_uuid(), NULL, 'aws-ap-southeast-1', 'Asia · Singapore', 'Singapore', 'managed', true),
    (gen_random_uuid(), NULL, 'aws-ap-northeast-1', 'Asia · Tokyo', 'Tokyo', 'managed', true),
    (gen_random_uuid(), NULL, 'aws-sa-east-1', 'South America · São Paulo', 'São Paulo', 'managed', true),
    (gen_random_uuid(), NULL, 'aws-us-east-2', 'US Central', 'Ohio', 'managed', true);
