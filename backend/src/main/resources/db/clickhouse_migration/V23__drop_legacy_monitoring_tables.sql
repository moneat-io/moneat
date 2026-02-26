-- Drop legacy fixed-column monitoring tables (replaced by metrics and containers)
DROP TABLE IF EXISTS system_metrics;
DROP TABLE IF EXISTS container_metrics;
