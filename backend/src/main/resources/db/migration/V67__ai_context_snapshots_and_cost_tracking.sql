-- AI context snapshots for enterprise AI module
CREATE TABLE ai_context_snapshots (
    id SERIAL PRIMARY KEY,
    conversation_id INTEGER REFERENCES ai_conversations(id) ON DELETE CASCADE,
    org_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    context_data JSONB NOT NULL,
    sources_summary JSONB NOT NULL,
    estimated_tokens INTEGER NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ DEFAULT NOW() + INTERVAL '1 hour'
);

CREATE INDEX idx_ai_context_snapshots_user ON ai_context_snapshots(user_id);
CREATE INDEX idx_ai_context_snapshots_expires ON ai_context_snapshots(expires_at);

-- Add cost tracking columns to ai_messages
ALTER TABLE ai_messages
    ADD COLUMN input_tokens INTEGER,
    ADD COLUMN output_tokens INTEGER,
    ADD COLUMN cost_usd NUMERIC(10, 6),
    ADD COLUMN provider VARCHAR(20);
