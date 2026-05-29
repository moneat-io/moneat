ALTER TABLE workflow_runs
    ADD COLUMN temporal_workflow_id VARCHAR(255),
    ADD COLUMN temporal_run_id VARCHAR(255);

CREATE INDEX idx_workflow_runs_temporal_workflow
    ON workflow_runs (temporal_workflow_id)
    WHERE temporal_workflow_id IS NOT NULL;
