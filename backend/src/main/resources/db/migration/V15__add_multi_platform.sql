-- Migration: Add multi-platform support
-- Adds platform_target column to project_keys and renames projects.platform to framework

-- Add platform_target column to project_keys (nullable for backward compatibility)
ALTER TABLE project_keys ADD COLUMN IF NOT EXISTS platform_target VARCHAR(50);

-- Add unique constraint: one key per platform per project
CREATE UNIQUE INDEX IF NOT EXISTS idx_project_keys_platform_target
  ON project_keys (project_id, platform_target)
  WHERE platform_target IS NOT NULL;

-- Rename platform -> framework on projects
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'projects' AND column_name = 'platform'
  ) THEN
    ALTER TABLE projects RENAME COLUMN platform TO framework;
  END IF;
END $$;
