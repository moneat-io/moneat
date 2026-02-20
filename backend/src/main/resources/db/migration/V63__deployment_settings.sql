-- Lightweight key-value table for deployment-level settings (e.g. telemetry deployment ID).
CREATE TABLE IF NOT EXISTS deployment_settings (
    key   VARCHAR(255) PRIMARY KEY,
    value TEXT NOT NULL
);
