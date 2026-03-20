-- Expand metric_type enum for OTLP metric types
ALTER TABLE metrics MODIFY COLUMN metric_type Enum8(
    'gauge' = 1, 'rate' = 2, 'count' = 3,
    'histogram' = 4, 'exp_histogram' = 5, 'summary' = 6, 'sum' = 7
);

-- Source discrimination
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT 'datadog';

-- OTLP sum-specific fields
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS is_monotonic UInt8 DEFAULT 0;
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS aggregation_temporality LowCardinality(String) DEFAULT '';

-- Histogram fields (zero-cost defaults for gauge/count/rate rows)
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS hist_count UInt64 DEFAULT 0;
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS hist_sum Nullable(Float64) DEFAULT NULL;
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS hist_min Nullable(Float64) DEFAULT NULL;
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS hist_max Nullable(Float64) DEFAULT NULL;
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS hist_bucket_counts Array(UInt64) DEFAULT [];
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS hist_explicit_bounds Array(Float64) DEFAULT [];

-- Resource context
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS resource_attributes Map(String, String) DEFAULT map();
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS service LowCardinality(String) DEFAULT '';
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS env LowCardinality(String) DEFAULT '';
ALTER TABLE metrics ADD COLUMN IF NOT EXISTS description String DEFAULT '';
