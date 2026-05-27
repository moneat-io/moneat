ALTER TABLE moneat.analytics_events
    ADD COLUMN IF NOT EXISTS user_id String DEFAULT '';

ALTER TABLE moneat.analytics_events
    ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT 'web';
