-- Drop legacy system tables in FK order
DROP TABLE IF EXISTS system_alert_template_states;
DROP TABLE IF EXISTS system_alert_settings;
DROP TABLE IF EXISTS system_alerts;
DROP TABLE IF EXISTS systems;

-- Remap SYSTEM_ALERT/SYSTEM_DOWN data before adding the new CHECK constraint
UPDATE alert_notification_preferences SET alert_source = 'HOST_ALERT' WHERE alert_source = 'SYSTEM_ALERT';
UPDATE alert_notification_preferences SET alert_source = 'HOST_DOWN' WHERE alert_source = 'SYSTEM_DOWN';

-- Update check constraint on alert_notification_preferences (data already remapped above)
ALTER TABLE alert_notification_preferences DROP CONSTRAINT IF EXISTS alert_notification_preferences_alert_source_check;
ALTER TABLE alert_notification_preferences ADD CONSTRAINT alert_notification_preferences_alert_source_check
    CHECK (alert_source IN ('HOST_ALERT', 'HOST_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT', 'DASHBOARD_ALERT'));

-- Update escalation_policy_alert_sources if the table and column exist
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'escalation_policy_alert_sources' AND column_name = 'alert_source'
    ) THEN
        UPDATE escalation_policy_alert_sources SET alert_source = 'HOST_ALERT' WHERE alert_source = 'SYSTEM_ALERT';
        UPDATE escalation_policy_alert_sources SET alert_source = 'HOST_DOWN' WHERE alert_source = 'SYSTEM_DOWN';
    END IF;
END $$;

-- Update incident_routing_rules if the table and column exist
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'incident_routing_rules' AND column_name = 'alert_source'
    ) THEN
        UPDATE incident_routing_rules SET alert_source = 'HOST_ALERT' WHERE alert_source = 'SYSTEM_ALERT';
        UPDATE incident_routing_rules SET alert_source = 'HOST_DOWN' WHERE alert_source = 'SYSTEM_DOWN';
    END IF;
END $$;

-- Update incident_event_log if the table and source column exist
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'incident_event_log' AND column_name = 'source'
    ) THEN
        UPDATE incident_event_log SET source = 'HOST_ALERT' WHERE source = 'SYSTEM_ALERT';
        UPDATE incident_event_log SET source = 'HOST_DOWN' WHERE source = 'SYSTEM_DOWN';
    END IF;
END $$;
