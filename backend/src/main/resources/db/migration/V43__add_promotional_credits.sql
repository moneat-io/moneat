-- Migration: Add promotional credits system
-- Description: Allow admins to grant bonus GB/units to organizations for promotions

-- Add bonus credits columns to subscriptions table
ALTER TABLE subscriptions
ADD COLUMN bonus_gb_bytes BIGINT DEFAULT 0,
ADD COLUMN bonus_units BIGINT DEFAULT 0,
ADD COLUMN bonus_granted_at TIMESTAMP,
ADD COLUMN bonus_granted_by INT REFERENCES users(id),
ADD COLUMN bonus_reason VARCHAR(500);

-- Create table to track promotional credit grants (audit trail)
CREATE TABLE promotional_credit_grants (
    id SERIAL PRIMARY KEY,
    organization_id INT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    subscription_id INT REFERENCES subscriptions(id) ON DELETE SET NULL,
    granted_by INT NOT NULL REFERENCES users(id),
    bonus_gb_bytes BIGINT NOT NULL DEFAULT 0,
    bonus_units BIGINT NOT NULL DEFAULT 0,
    reason VARCHAR(500),
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_promotional_grants_org ON promotional_credit_grants(organization_id);
CREATE INDEX idx_promotional_grants_granted_at ON promotional_credit_grants(granted_at);

-- Comment for documentation
COMMENT ON COLUMN subscriptions.bonus_gb_bytes IS 'Promotional bonus storage in bytes (e.g., 5GB = 5368709120 bytes)';
COMMENT ON COLUMN subscriptions.bonus_units IS 'Promotional bonus event units';
COMMENT ON TABLE promotional_credit_grants IS 'Audit trail of all promotional credits granted to organizations';
