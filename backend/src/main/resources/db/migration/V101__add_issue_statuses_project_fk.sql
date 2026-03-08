ALTER TABLE issue_statuses
    ADD CONSTRAINT fk_issue_statuses_project
    FOREIGN KEY (project_id) REFERENCES projects(id)
    ON DELETE CASCADE;
