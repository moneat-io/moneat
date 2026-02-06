-- Migration: Add auth_tokens table for user-scoped authentication tokens
-- This enables build-time operations like uploading source maps and creating releases

CREATE TABLE IF NOT EXISTS auth_tokens (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE, -- SHA256 hash of the actual token
    name VARCHAR(255) NOT NULL, -- User-friendly name for the token
    scopes TEXT[] NOT NULL, -- Array of permission scopes
    last_used_at TIMESTAMP NULL, -- Track last usage for security monitoring
    expires_at TIMESTAMP NULL, -- Optional expiration date
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX idx_auth_tokens_user ON auth_tokens(user_id);
CREATE INDEX idx_auth_tokens_hash ON auth_tokens(token_hash);
CREATE INDEX idx_auth_tokens_expires ON auth_tokens(expires_at);

-- Comments for documentation
COMMENT ON TABLE auth_tokens IS 'User-scoped authentication tokens for build-time operations';
COMMENT ON COLUMN auth_tokens.token_hash IS 'SHA256 hash of the actual token (never store plaintext)';
COMMENT ON COLUMN auth_tokens.scopes IS 'Permission scopes: project:read, project:write, releases:read, releases:write, sourcemaps:read, sourcemaps:write, event:read, org:read';
