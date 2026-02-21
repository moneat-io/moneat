-- Add ON DELETE CASCADE to user_sso_links FK so that deleting an sso_configuration
-- automatically removes linked user_sso_links. Fixes org deletion when SSO links exist.
ALTER TABLE user_sso_links
  DROP CONSTRAINT IF EXISTS user_sso_links_sso_configuration_id_fkey,
  ADD CONSTRAINT user_sso_links_sso_configuration_id_fkey
    FOREIGN KEY (sso_configuration_id) REFERENCES sso_configurations(id) ON DELETE CASCADE;
