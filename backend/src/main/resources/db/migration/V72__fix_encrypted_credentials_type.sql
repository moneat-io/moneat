-- Change encrypted_credentials from JSONB to TEXT since it stores
-- AES-256-GCM encrypted Base64 strings, not JSON.
ALTER TABLE custom_data_sources ALTER COLUMN encrypted_credentials TYPE TEXT USING encrypted_credentials::TEXT;
