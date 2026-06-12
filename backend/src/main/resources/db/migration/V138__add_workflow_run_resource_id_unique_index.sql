DROP INDEX IF EXISTS idx_workflow_runs_resource_id;
CREATE UNIQUE INDEX idx_workflow_runs_resource_id ON workflow_runs(resource_id);
