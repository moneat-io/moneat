-- Add OAuth provider fields to users table
ALTER TABLE users ADD COLUMN oauth_provider VARCHAR(20);
ALTER TABLE users ADD COLUMN oauth_provider_id VARCHAR(512);

-- Create index for faster OAuth lookups
CREATE INDEX idx_users_oauth_provider ON users(oauth_provider, oauth_provider_id);
