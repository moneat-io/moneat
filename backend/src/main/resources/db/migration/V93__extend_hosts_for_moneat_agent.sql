-- Extend hosts table for Moneat Agent support (unified with Datadog-style hosts)
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS agent_key_hash VARCHAR(255);
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS arch VARCHAR(20);
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'pending';
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS idx_hosts_agent_key ON hosts(agent_key_hash) WHERE agent_key_hash IS NOT NULL;
