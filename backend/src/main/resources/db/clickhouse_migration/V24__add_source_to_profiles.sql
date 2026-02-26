-- Add source column to profiles table to distinguish SDK origin (sentry vs datadog)
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS source String DEFAULT 'datadog';
