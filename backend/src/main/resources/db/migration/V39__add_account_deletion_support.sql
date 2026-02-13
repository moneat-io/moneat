-- Add soft delete support for users and organizations
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- Add index for filtering out deleted records
CREATE INDEX idx_users_deleted_at ON users(deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_organizations_deleted_at ON organizations(deleted_at) WHERE deleted_at IS NULL;

-- Add audit fields for tracking who initiated deletion
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS deleted_by INTEGER REFERENCES users(id);
