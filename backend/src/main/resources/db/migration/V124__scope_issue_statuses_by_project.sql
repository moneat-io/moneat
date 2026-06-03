ALTER TABLE issue_statuses
    DROP CONSTRAINT IF EXISTS issue_statuses_issue_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_issue_statuses_issue_project
    ON issue_statuses(issue_id, project_id);
