-- Track SLA execution independently from recurring reminder escalation.
ALTER TABLE native_incident_follow_ups
    ADD COLUMN sla_fired_at TIMESTAMP;
