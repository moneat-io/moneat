-- Add referral_source column to organizations table to track how users heard about Moneat
ALTER TABLE organizations ADD COLUMN referral_source VARCHAR(100);
