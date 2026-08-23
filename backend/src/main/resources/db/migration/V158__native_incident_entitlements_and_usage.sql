CREATE TABLE native_incident_plan_entitlements (
    tier_name VARCHAR(50) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    quotas JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_native_incident_plan_quota_object CHECK (jsonb_typeof(quotas) = 'object')
);

INSERT INTO native_incident_plan_entitlements (tier_name, enabled, quotas)
VALUES
    ('FREE', FALSE, '{}'::jsonb),
    ('PRO', TRUE, '{
          "native_incidents":25,"responders":5,"integrations":2,"ai_runs":25,
          "transcription_minutes":120,"retained_evidence_bytes":1073741824,
          "enabled_alert_routes":10,"active_alert_groups":50,"route_created_incidents":25,
          "automatic_correlations":250,"ai_assisted_correlations":25
        }'::jsonb),
    ('TEAM', TRUE, '{
          "native_incidents":250,"responders":25,"integrations":10,"ai_runs":500,
          "transcription_minutes":2000,"retained_evidence_bytes":26843545600,
          "enabled_alert_routes":100,"active_alert_groups":1000,"route_created_incidents":500,
          "automatic_correlations":10000,"ai_assisted_correlations":1000
        }'::jsonb),
    ('BUSINESS', TRUE, '{
          "native_incidents":2500,"responders":250,"integrations":50,"ai_runs":10000,
          "transcription_minutes":50000,"retained_evidence_bytes":536870912000,
          "enabled_alert_routes":1000,"active_alert_groups":10000,"route_created_incidents":10000,
          "automatic_correlations":250000,"ai_assisted_correlations":25000
        }'::jsonb);

CREATE TABLE native_incident_usage_events (
    id BIGSERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    quota_key VARCHAR(80) NOT NULL,
    window_key VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    quantity BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_usage_event
        UNIQUE (organization_id, quota_key, window_key, idempotency_key)
);

CREATE INDEX idx_native_incident_usage_events_org_recorded
    ON native_incident_usage_events(organization_id, recorded_at DESC);

CREATE TABLE native_incident_usage_counters (
    id BIGSERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    quota_key VARCHAR(80) NOT NULL,
    window_key VARCHAR(20) NOT NULL,
    used BIGINT NOT NULL DEFAULT 0,
    limit_snapshot BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_native_incident_usage_counter UNIQUE (organization_id, quota_key, window_key),
    CONSTRAINT chk_native_incident_usage_counter_used CHECK (used >= 0),
    CONSTRAINT chk_native_incident_usage_counter_limit CHECK (limit_snapshot >= 0)
);
