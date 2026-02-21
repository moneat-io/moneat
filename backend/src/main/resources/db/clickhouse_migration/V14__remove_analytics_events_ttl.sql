-- Remove hardcoded 730-day TTL from analytics_events so retention is controlled
-- by plan-configured analyticsRetentionDays in RetentionBackgroundService.
-- The default tier config allows up to 1095 days; TTL would delete earlier than promised.

ALTER TABLE moneat.analytics_events REMOVE TTL;
