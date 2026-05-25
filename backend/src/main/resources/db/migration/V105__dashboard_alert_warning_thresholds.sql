-- Add optional warning thresholds to dashboard widget alerts.
--
-- Existing threshold values continue to represent the error threshold for
-- backward compatibility with saved alerts and API clients.

ALTER TABLE dashboard_widget_alerts
    ADD COLUMN warning_threshold DOUBLE PRECISION,
    ADD COLUMN last_triggered_level VARCHAR(20);

ALTER TABLE dashboard_widget_alerts
    ADD CONSTRAINT check_dashboard_widget_alert_last_triggered_level
        CHECK (last_triggered_level IS NULL OR last_triggered_level IN ('WARNING', 'ERROR'));
