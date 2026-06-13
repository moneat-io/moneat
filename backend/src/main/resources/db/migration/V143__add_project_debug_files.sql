-- Project debug information files (DIFs) for the Sentry-compatible debug-files upload API.
-- Backs POST /api/0/projects/{org}/{project}/files/difs/assemble/, used by sentry-cli
-- upload-proguard (Android R8/ProGuard mappings) and the Sentry Android Gradle plugin's
-- uploadSentryProguardMappings task. Files are chunk-uploaded (see V48 file_blobs), then
-- assembled and recorded here, keyed by their ProGuard/debug UUID for later symbolication.
CREATE TABLE IF NOT EXISTS project_debug_files (
    id SERIAL PRIMARY KEY,
    resource_id UUID NOT NULL DEFAULT gen_random_uuid(),
    -- FK targets the real table `services` (V125 renamed projects -> services and left
    -- `projects` as a view; a foreign key cannot reference a view).
    project_id BIGINT NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    debug_id VARCHAR(64),
    checksum VARCHAR(40) NOT NULL,
    file_type VARCHAR(32) NOT NULL DEFAULT 'proguard',
    object_name VARCHAR(500),
    size BIGINT NOT NULL DEFAULT 0,
    storage_path VARCHAR(500) NOT NULL,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

-- One stored file per (project, content checksum); makes re-uploads idempotent.
CREATE UNIQUE INDEX IF NOT EXISTS idx_project_debug_files_proj_checksum
    ON project_debug_files(project_id, checksum);

-- Lookup path for symbolication: resolve a debug image's UUID to its mapping file.
CREATE INDEX IF NOT EXISTS idx_project_debug_files_proj_debug_id
    ON project_debug_files(project_id, debug_id);
