ALTER TABLE dashboard_folders ADD COLUMN resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE dashboards ADD COLUMN resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE dashboard_widgets ADD COLUMN resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE dashboard_widget_alerts ADD COLUMN resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE custom_data_sources ADD COLUMN resource_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX idx_dashboard_folders_resource_id ON dashboard_folders(resource_id);
CREATE UNIQUE INDEX idx_dashboards_resource_id ON dashboards(resource_id);
CREATE UNIQUE INDEX idx_dashboard_widgets_resource_id ON dashboard_widgets(resource_id);
CREATE UNIQUE INDEX idx_dashboard_widget_alerts_resource_id ON dashboard_widget_alerts(resource_id);
CREATE UNIQUE INDEX idx_custom_data_sources_resource_id ON custom_data_sources(resource_id);
