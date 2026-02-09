CREATE TABLE IF NOT EXISTS log_sources (
    id SERIAL PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_type VARCHAR(50) NOT NULL,
    source_name VARCHAR(255),
    config JSONB,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_log_sources_project ON log_sources(project_id);
CREATE INDEX IF NOT EXISTS idx_log_sources_active ON log_sources(project_id, is_active);
