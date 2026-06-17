ALTER TABLE projects ADD COLUMN resource_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX idx_projects_resource_id ON projects(resource_id);
