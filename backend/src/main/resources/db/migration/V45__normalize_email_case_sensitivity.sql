-- Normalize all existing emails to lowercase to prevent duplicates
UPDATE users SET email = LOWER(TRIM(email));

-- Drop the existing unique constraint on email
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;

-- Add a case-insensitive unique constraint using a unique index with LOWER()
-- This prevents future duplicates like "Test@example.com" and "test@example.com"
CREATE UNIQUE INDEX users_email_lower_unique ON users(LOWER(email));

-- Also update the existing index to use LOWER() for better query performance
DROP INDEX IF EXISTS idx_users_email;
CREATE INDEX idx_users_email ON users(LOWER(email));

-- Normalize invitation emails as well
UPDATE org_invitations SET email = LOWER(TRIM(email));
CREATE INDEX IF NOT EXISTS idx_org_invitations_email_lower ON org_invitations(LOWER(email));
