DROP INDEX IF EXISTS idx_workflows_resource_id;
CREATE UNIQUE INDEX idx_workflows_resource_id ON workflows(resource_id);
