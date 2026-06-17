-- Rich per-run detail for a single synthetic result: per-assertion expected-vs-actual,
-- request/response capture (redacted), timing phases, and browser steps/console/network.
-- Stored as a JSON blob keyed by result_id to keep synthetic_results lean.

CREATE TABLE IF NOT EXISTS synthetic_run_details (
    result_id UUID,
    organization_id UInt64,
    test_id String DEFAULT '',
    location_code String DEFAULT '',
    details String DEFAULT '',
    timestamp DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (organization_id, result_id)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;
