-- V59: Add phone numbers and Twilio SMS/call fallback support

-- Add phone number to users (E.164 format, e.g. +15551234567)
ALTER TABLE users ADD COLUMN phone_number VARCHAR(20) NULL;

-- Add SMS/call fallback delay per escalation step
-- 0 or NULL disables SMS/call for that step; default 2 minutes
ALTER TABLE escalation_steps ADD COLUMN sms_call_fallback_delay_minutes INTEGER NOT NULL DEFAULT 2;

-- Track Twilio SMS and call notifications sent (audit log)
CREATE TABLE twilio_notifications_sent (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    incident_id INTEGER REFERENCES incidents(id) ON DELETE SET NULL,
    channel VARCHAR(10) NOT NULL, -- 'sms' or 'call'
    twilio_sid VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'queued',
    phone_number VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT check_channel CHECK (channel IN ('sms', 'call'))
);

CREATE INDEX idx_twilio_notifications_user ON twilio_notifications_sent(user_id);
CREATE INDEX idx_twilio_notifications_incident ON twilio_notifications_sent(incident_id);
