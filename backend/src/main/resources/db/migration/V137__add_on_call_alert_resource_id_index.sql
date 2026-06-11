DROP INDEX IF EXISTS idx_on_call_alerts_resource_id;
CREATE UNIQUE INDEX idx_on_call_alerts_resource_id ON on_call_alerts(resource_id);
