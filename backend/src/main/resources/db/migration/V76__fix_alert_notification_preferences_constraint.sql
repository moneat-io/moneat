-- Fix alert_notification_preferences constraint to include DASHBOARD_ALERT
-- This fixes the constraint name issue from V75

-- Drop any incorrectly named constraint that might have been created
ALTER TABLE alert_notification_preferences
    DROP CONSTRAINT IF EXISTS alert_notification_preferences_check;

-- Drop and recreate the correctly named constraint with DASHBOARD_ALERT included
ALTER TABLE alert_notification_preferences
    DROP CONSTRAINT IF EXISTS alert_notification_preferences_alert_source_check,
    ADD CONSTRAINT alert_notification_preferences_alert_source_check
        CHECK (alert_source IN ('SYSTEM_ALERT', 'SYSTEM_DOWN', 'UPTIME_MONITOR', 'ERROR_ALERT', 'DASHBOARD_ALERT'));
