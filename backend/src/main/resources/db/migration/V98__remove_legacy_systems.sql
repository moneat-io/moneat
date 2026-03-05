-- Drop legacy system tables in FK order
DROP TABLE IF EXISTS system_alert_template_states;
DROP TABLE IF EXISTS system_alert_settings;
DROP TABLE IF EXISTS system_alerts;
DROP TABLE IF EXISTS systems;

-- Update check constraints on alert_notification_preferences
ALTER TABLE alert_notification_preferences DROP CONSTRAINT IF EXISTS alert_notification_preferences_alert_source_check;
ALTER TABLE alert_notification_preferences ADD CONSTRAINT alert_notification_preferences_alert_source_check 
    CHECK (alert_source IN ('HOST_ALERT', 'HOST_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT', 'DASHBOARD_ALERT'));

-- Update existing data
UPDATE alert_notification_preferences SET alert_source = 'HOST_ALERT' WHERE alert_source = 'SYSTEM_ALERT';
UPDATE alert_notification_preferences SET alert_source = 'HOST_DOWN' WHERE alert_source = 'SYSTEM_DOWN';

-- Update escalation_policy_alert_sources if it exists
UPDATE escalation_policy_alert_sources SET alert_source = 'HOST_ALERT' WHERE alert_source = 'SYSTEM_ALERT';
UPDATE escalation_policy_alert_sources SET alert_source = 'HOST_DOWN' WHERE alert_source = 'SYSTEM_DOWN';

-- Update incident_routing_rules if alert_source column exists
UPDATE incident_routing_rules SET alert_source = 'HOST_ALERT' WHERE alert_source = 'SYSTEM_ALERT';
UPDATE incident_routing_rules SET alert_source = 'HOST_DOWN' WHERE alert_source = 'SYSTEM_DOWN';

-- Update incident_event_log if alert_source column exists
UPDATE incident_event_log SET source = 'HOST_ALERT' WHERE source = 'SYSTEM_ALERT';
UPDATE incident_event_log SET source = 'HOST_DOWN' WHERE source = 'SYSTEM_DOWN';
