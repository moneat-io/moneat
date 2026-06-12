-- Extend synthetic_tests for the redesign: service/environment grouping, multi-location
-- selection, structured alert conditions + recipients, and browser/E2E step definitions.

ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS service VARCHAR(255);
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS environment VARCHAR(255);
-- JSON array of managed/private location codes this test runs from.
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS locations TEXT NOT NULL DEFAULT '[]';
-- JSON object: structured trigger condition (consecutive checks, M-of-N locations, renotify, ...).
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS alert_config TEXT;
-- JSON array of {type, target} recipients (slack/email/pagerduty/webhook).
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS alert_recipients TEXT NOT NULL DEFAULT '[]';
-- JSON array of recorded browser steps (navigate/click/type/assert/wait) for browser tests.
ALTER TABLE synthetic_tests ADD COLUMN IF NOT EXISTS browser_steps TEXT;
