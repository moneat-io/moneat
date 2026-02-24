-- Multi-query support: each widget can now have multiple query configurations
-- Each query has a refId (A, B, C...) allowing different data sources per widget

ALTER TABLE dashboard_widgets ADD COLUMN query_configs JSONB;

-- Migrate existing single query_config into query_configs array
UPDATE dashboard_widgets SET query_configs = jsonb_build_array(query_config);

-- Make query_configs NOT NULL now that all rows have data
ALTER TABLE dashboard_widgets ALTER COLUMN query_configs SET NOT NULL;
ALTER TABLE dashboard_widgets ALTER COLUMN query_configs SET DEFAULT '[]';

-- Keep query_config column temporarily for backward compatibility
-- It can be dropped in a future migration after all clients are updated
