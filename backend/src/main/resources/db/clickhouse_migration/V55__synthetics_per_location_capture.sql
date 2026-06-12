-- Per-location, per-assertion result capture for the synthetics redesign.
-- One synthetic_results row is recorded per (test, location, attempt).

ALTER TABLE synthetic_results ADD COLUMN IF NOT EXISTS location_code String DEFAULT '';
ALTER TABLE synthetic_results ADD COLUMN IF NOT EXISTS attempt UInt8 DEFAULT 1;
ALTER TABLE synthetic_results ADD COLUMN IF NOT EXISTS status_code UInt16 DEFAULT 0;
ALTER TABLE synthetic_results ADD COLUMN IF NOT EXISTS assertions_total UInt16 DEFAULT 0;
ALTER TABLE synthetic_results ADD COLUMN IF NOT EXISTS assertions_failed UInt16 DEFAULT 0;
ALTER TABLE synthetic_results ADD COLUMN IF NOT EXISTS resolved_ip String DEFAULT '';

ALTER TABLE synthetic_results MODIFY COLUMN test_type Enum8('api' = 1, 'browser' = 2, 'multistep' = 3, 'ssl' = 4, 'dns' = 5, 'tcp' = 6, 'udp' = 7, 'ping' = 8);
