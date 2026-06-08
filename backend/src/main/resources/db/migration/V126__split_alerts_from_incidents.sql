-- Split on-call alerts from declared incidents.
-- Alerts use P0-P5 priority. Declared incidents use SEV-0-SEV-4 severity.

ALTER TABLE incidents RENAME TO on_call_alerts;
ALTER TABLE incident_timeline RENAME TO on_call_alert_timeline;

ALTER TABLE on_call_alerts RENAME COLUMN incident_id TO declared_incident_id;
ALTER TABLE on_call_alerts RENAME COLUMN priority_level TO priority;
ALTER TABLE on_call_alert_timeline RENAME COLUMN incident_id TO alert_id;
ALTER TABLE twilio_notifications_sent RENAME COLUMN incident_id TO alert_id;

ALTER TABLE alert_priorities RENAME COLUMN severity TO priority;

UPDATE alert_priorities
SET priority = CASE UPPER(REPLACE(priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(priority, '_', '-'))
END;

UPDATE alert_priorities
SET label = priority
WHERE UPPER(REPLACE(label, '_', '-')) IN (
    'CRITICAL', 'HIGH', 'MEDIUM', 'LOW',
    'SEV-0', 'SEV0', 'SEV-1', 'SEV1', 'SEV-2', 'SEV2', 'SEV-3', 'SEV3', 'SEV-4', 'SEV4',
    'P0', 'P1', 'P2', 'P3', 'P4', 'P5'
);

ALTER TABLE alert_priorities DROP COLUMN priority_level;

UPDATE on_call_alerts
SET priority = CASE UPPER(REPLACE(priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(priority, '_', '-'))
END;

UPDATE on_call_incidents
SET severity = CASE UPPER(REPLACE(severity, '_', '-'))
    WHEN 'P0' THEN 'SEV-0'
    WHEN 'P1' THEN 'SEV-1'
    WHEN 'P2' THEN 'SEV-2'
    WHEN 'P3' THEN 'SEV-3'
    WHEN 'P4' THEN 'SEV-4'
    WHEN 'P5' THEN 'SEV-4'
    WHEN 'CRITICAL' THEN 'SEV-0'
    WHEN 'HIGH' THEN 'SEV-1'
    WHEN 'MEDIUM' THEN 'SEV-2'
    WHEN 'LOW' THEN 'SEV-3'
    WHEN 'SEV0' THEN 'SEV-0'
    WHEN 'SEV-0' THEN 'SEV-0'
    WHEN 'SEV1' THEN 'SEV-1'
    WHEN 'SEV-1' THEN 'SEV-1'
    WHEN 'SEV2' THEN 'SEV-2'
    WHEN 'SEV-2' THEN 'SEV-2'
    WHEN 'SEV3' THEN 'SEV-3'
    WHEN 'SEV-3' THEN 'SEV-3'
    WHEN 'SEV4' THEN 'SEV-4'
    WHEN 'SEV-4' THEN 'SEV-4'
    ELSE severity
END;

ALTER TABLE dashboard_widget_alerts RENAME COLUMN incident_severity TO alert_priority;
ALTER TABLE incident_routing_rules RENAME COLUMN incident_severity TO alert_priority;
ALTER TABLE incident_event_log RENAME COLUMN incident_severity TO alert_priority;
ALTER TABLE organization_alert_templates RENAME COLUMN incident_severity TO alert_priority;
ALTER TABLE uptime_monitors RENAME COLUMN incident_severity TO alert_priority;
ALTER TABLE host_alerts RENAME COLUMN incident_severity TO alert_priority;

UPDATE dashboard_widget_alerts
SET alert_priority = CASE UPPER(REPLACE(alert_priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(alert_priority, '_', '-'))
END
WHERE alert_priority IS NOT NULL;

UPDATE incident_routing_rules
SET alert_priority = CASE UPPER(REPLACE(alert_priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(alert_priority, '_', '-'))
END;

UPDATE incident_event_log
SET alert_priority = CASE UPPER(REPLACE(alert_priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(alert_priority, '_', '-'))
END;

UPDATE organization_alert_templates
SET alert_priority = CASE UPPER(REPLACE(alert_priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(alert_priority, '_', '-'))
END
WHERE alert_priority IS NOT NULL;

UPDATE uptime_monitors
SET alert_priority = CASE UPPER(REPLACE(alert_priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(alert_priority, '_', '-'))
END
WHERE alert_priority IS NOT NULL;

UPDATE host_alerts
SET alert_priority = CASE UPPER(REPLACE(alert_priority, '_', '-'))
    WHEN 'CRITICAL' THEN 'P0'
    WHEN 'HIGH' THEN 'P1'
    WHEN 'MEDIUM' THEN 'P2'
    WHEN 'LOW' THEN 'P3'
    WHEN 'SEV-0' THEN 'P0'
    WHEN 'SEV0' THEN 'P0'
    WHEN 'SEV-1' THEN 'P0'
    WHEN 'SEV1' THEN 'P0'
    WHEN 'SEV-2' THEN 'P1'
    WHEN 'SEV2' THEN 'P1'
    WHEN 'SEV-3' THEN 'P2'
    WHEN 'SEV3' THEN 'P2'
    WHEN 'SEV-4' THEN 'P3'
    WHEN 'SEV4' THEN 'P3'
    ELSE UPPER(REPLACE(alert_priority, '_', '-'))
END
WHERE alert_priority IS NOT NULL;
