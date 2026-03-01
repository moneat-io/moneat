-- Add projections for common log aggregation patterns.
-- ClickHouse automatically uses these when a query matches their shape.

ALTER TABLE logs ADD PROJECTION proj_level_histogram (
    SELECT organization_id, toStartOfHour(timestamp) AS hour,
           level, service, count() AS cnt
    GROUP BY organization_id, hour, level, service
);

ALTER TABLE logs ADD PROJECTION proj_service_counts (
    SELECT organization_id, toStartOfDay(timestamp) AS day,
           service, environment, count() AS cnt
    GROUP BY organization_id, day, service, environment
);

ALTER TABLE logs MATERIALIZE PROJECTION proj_level_histogram;

ALTER TABLE logs MATERIALIZE PROJECTION proj_service_counts;
