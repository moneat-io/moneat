-- Add org_invitations table for invite-based member onboarding
CREATE TABLE org_invitations (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'member',
    invited_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    expires_at BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT valid_role CHECK (role IN ('owner', 'admin', 'member')),
    CONSTRAINT valid_status CHECK (status IN ('pending', 'accepted', 'expired', 'revoked'))
);

-- Ensure only one pending invitation per email per org
CREATE UNIQUE INDEX idx_org_invitations_pending ON org_invitations(organization_id, email) WHERE status = 'pending';

-- Index for token lookups
CREATE INDEX idx_org_invitations_token ON org_invitations(token);

-- Index for organization lookups
CREATE INDEX idx_org_invitations_org_id ON org_invitations(organization_id);

-- Add CHECK constraint to memberships.role to enforce valid values
ALTER TABLE memberships ADD CONSTRAINT valid_membership_role CHECK (role IN ('owner', 'admin', 'member'));
