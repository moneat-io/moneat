CREATE TABLE IF NOT EXISTS user_legal_acceptances (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_type VARCHAR(20) NOT NULL,
    document_version VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(64),
    user_agent TEXT,
    CONSTRAINT chk_user_legal_acceptances_document_type CHECK (document_type IN ('terms', 'privacy')),
    CONSTRAINT uq_user_legal_acceptances_user_doc_version UNIQUE (user_id, document_type, document_version)
);

CREATE INDEX IF NOT EXISTS idx_user_legal_acceptances_user_id
    ON user_legal_acceptances(user_id);

CREATE INDEX IF NOT EXISTS idx_user_legal_acceptances_doc_type_version
    ON user_legal_acceptances(document_type, document_version);
