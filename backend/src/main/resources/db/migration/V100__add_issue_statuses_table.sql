CREATE TABLE IF NOT EXISTS issue_statuses (
    id SERIAL PRIMARY KEY,
    issue_id VARCHAR(64) NOT NULL UNIQUE,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'unresolved',
    substatus VARCHAR(64),
    status_detail JSONB,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by INT REFERENCES users(id)
);

CREATE INDEX idx_issue_statuses_project ON issue_statuses(project_id, status);
