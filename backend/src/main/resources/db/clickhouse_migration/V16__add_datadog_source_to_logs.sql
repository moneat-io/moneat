-- Add 'datadog' source type to logs table for DD-compatible log ingestion
ALTER TABLE logs MODIFY COLUMN source Enum8(
    'sdk' = 1,
    'agent_stdout' = 2,
    'agent_stderr' = 3,
    'otlp' = 4,
    'datadog' = 5
);
