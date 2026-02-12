-- V35: Add VIEWED event type to incident timeline
-- This allows tracking when users view incidents for the first time

ALTER TABLE incident_timeline DROP CONSTRAINT check_event_type;

ALTER TABLE incident_timeline ADD CONSTRAINT check_event_type CHECK (event_type IN (
    'TRIGGERED',
    'ESCALATED',
    'ACKNOWLEDGED',
    'RESOLVED',
    'REASSIGNED',
    'NOTE_ADDED',
    'STEP_TIMEOUT',
    'NOTIFICATION_SENT',
    'VIEWED'
));
