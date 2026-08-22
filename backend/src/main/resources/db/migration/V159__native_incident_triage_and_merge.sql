-- Triage incidents may stay unclassified until acceptance, and merging becomes a
-- terminal outcome that is distinct from cancellation and decline.

ALTER TABLE on_call_incidents
    ALTER COLUMN severity DROP NOT NULL;

ALTER TABLE on_call_incidents
    ADD COLUMN merged_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN merged_into_incident_id INTEGER
        REFERENCES on_call_incidents(id) ON DELETE CASCADE;

ALTER TABLE on_call_incidents
    DROP CONSTRAINT IF EXISTS chk_native_incident_status;

ALTER TABLE on_call_incidents
    ADD CONSTRAINT chk_native_incident_status CHECK (
        status IN (
            'TRIAGE', 'ACTIVE', 'RESOLVED', 'POST_INCIDENT',
            'CLOSED', 'CANCELLED', 'DECLINED', 'MERGED'
        )
    ),
    -- Severity is only required once the incident has been accepted out of triage.
    ADD CONSTRAINT chk_native_incident_triage_severity CHECK (
        severity IS NOT NULL OR accepted_at IS NULL
    ),
    ADD CONSTRAINT chk_native_incident_merged_state CHECK (
        (status = 'MERGED' AND merged_at IS NOT NULL AND merged_into_incident_id IS NOT NULL)
        OR (status <> 'MERGED' AND merged_at IS NULL AND merged_into_incident_id IS NULL)
    ),
    ADD CONSTRAINT chk_native_incident_merge_target CHECK (
        merged_into_incident_id IS NULL OR merged_into_incident_id <> id
    );

CREATE INDEX idx_on_call_incidents_merged_into
    ON on_call_incidents(merged_into_incident_id)
    WHERE merged_into_incident_id IS NOT NULL;
