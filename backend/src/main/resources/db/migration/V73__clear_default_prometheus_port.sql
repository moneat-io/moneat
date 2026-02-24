-- Moneat - observability platform
-- Copyright (C) 2026 Moneat
--
-- Clear default port 9090 from existing Prometheus data sources.
-- Hosts behind reverse proxies (no explicit port in hostname) should have NULL port.

UPDATE custom_data_sources
SET port = NULL
WHERE source_type = 'prometheus'
  AND port = 9090
  AND host NOT LIKE '%:9090%';
