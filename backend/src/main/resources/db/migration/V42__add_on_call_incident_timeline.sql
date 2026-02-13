CREATE TABLE on_call_incident_timeline (
    id SERIAL PRIMARY KEY,
    incident_id INTEGER NOT NULL REFERENCES on_call_incidents(id) ON DELETE CASCADE,
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('DECLARED', 'NOTE_ADDED', 'RESOLVED', 'ALERT_LINKED')),
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    details JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_on_call_incident_timeline_incident_id ON on_call_incident_timeline(incident_id);
CREATE INDEX idx_on_call_incident_timeline_created_at ON on_call_incident_timeline(created_at);
