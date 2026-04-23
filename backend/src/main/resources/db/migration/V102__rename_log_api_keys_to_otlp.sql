ALTER TABLE log_api_keys RENAME TO otlp_api_keys;
ALTER INDEX IF EXISTS idx_log_api_keys_org RENAME TO idx_otlp_api_keys_org;
ALTER INDEX IF EXISTS idx_log_api_keys_prefix RENAME TO idx_otlp_api_keys_prefix;
ALTER INDEX IF EXISTS idx_log_api_keys_active RENAME TO idx_otlp_api_keys_active;
