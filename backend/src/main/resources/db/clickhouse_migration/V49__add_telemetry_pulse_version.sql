-- Track the Moneat release version reported by anonymous self-hosted telemetry pulses.

ALTER TABLE telemetry_pulses
    ADD COLUMN IF NOT EXISTS version LowCardinality(String) DEFAULT '' AFTER deployment_id;
