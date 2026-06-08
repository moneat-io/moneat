ALTER TABLE user_feedback
    ADD COLUMN IF NOT EXISTS source_type LowCardinality(String) DEFAULT 'sentry' AFTER status;

ALTER TABLE user_feedback
    ADD COLUMN IF NOT EXISTS source_name String DEFAULT 'Sentry-compatible SDK' AFTER source_type;

ALTER TABLE user_feedback
    ADD COLUMN IF NOT EXISTS source_event_name String DEFAULT 'feedback' AFTER source_name;

ALTER TABLE user_feedback
    ADD COLUMN IF NOT EXISTS trace_id String DEFAULT '' AFTER source_event_name;

ALTER TABLE user_feedback
    ADD COLUMN IF NOT EXISTS span_id String DEFAULT '' AFTER trace_id;

ALTER TABLE user_feedback
    ADD COLUMN IF NOT EXISTS resource_attributes Map(String, String) DEFAULT map() AFTER span_id;
