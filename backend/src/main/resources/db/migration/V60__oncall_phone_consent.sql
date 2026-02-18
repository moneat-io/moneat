-- Add on-call phone consent fields to users table
ALTER TABLE users
    ADD COLUMN oncall_phone_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN oncall_phone_consented_at TIMESTAMPTZ,
    ADD COLUMN oncall_phone_consent_version VARCHAR(50),
    ADD COLUMN oncall_phone_consent_ip VARCHAR(45),
    ADD COLUMN oncall_phone_consent_user_agent TEXT,
    ADD COLUMN oncall_phone_opted_out_at TIMESTAMPTZ;

-- Append-only audit log for on-call phone consent events
CREATE TABLE oncall_phone_consent_events (
    id                SERIAL PRIMARY KEY,
    user_id           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    phone_number      VARCHAR(20) NOT NULL,
    event_type        VARCHAR(20) NOT NULL, -- OPT_IN, OPT_OUT, PHONE_REMOVED
    consent_version   VARCHAR(50),
    ip_address        VARCHAR(45),
    user_agent        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_oncall_consent_events_user_id ON oncall_phone_consent_events(user_id);
