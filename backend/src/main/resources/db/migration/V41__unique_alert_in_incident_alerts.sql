-- Add unique constraint on alert_id to prevent duplicate incident declarations for the same alert
CREATE UNIQUE INDEX uq_incident_alerts_alert_id ON on_call_incident_alerts (alert_id);
