CREATE TABLE alert_silence_periods (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id),
    reason VARCHAR(255),
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_silence_periods_org ON alert_silence_periods(organization_id);
CREATE INDEX idx_alert_silence_periods_active ON alert_silence_periods(organization_id, starts_at, ends_at);
