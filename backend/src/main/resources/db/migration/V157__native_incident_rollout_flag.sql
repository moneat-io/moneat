INSERT INTO feature_flag_environments (organization_id, key, name)
SELECT o.id, defaults.key, defaults.name
FROM organizations o
CROSS JOIN (
    VALUES
        ('production', 'Production'),
        ('staging', 'Staging'),
        ('development', 'Development')
) AS defaults(key, name)
ON CONFLICT (organization_id, key) DO NOTHING;

INSERT INTO feature_flags (
    organization_id,
    key,
    name,
    description,
    value_type,
    client_visible,
    tags
)
SELECT
    o.id,
    'moneat.system.native-incident-response',
    'Native incident response',
    'Controls native incident response independently of external incident-provider passthrough.',
    'BOOLEAN',
    FALSE,
    '["system","incident-response","rollout"]'::jsonb
FROM organizations o
ON CONFLICT (organization_id, key) DO NOTHING;

INSERT INTO feature_flag_variants (flag_id, key, name, value_json, sort_order)
SELECT f.id, variants.key, variants.name, variants.value_json, variants.sort_order
FROM feature_flags f
CROSS JOIN (
    VALUES
        ('disabled', 'Disabled', 'false'::jsonb, 0),
        ('enabled', 'Enabled', 'true'::jsonb, 1)
) AS variants(key, name, value_json, sort_order)
WHERE f.key = 'moneat.system.native-incident-response'
  AND f.value_type = 'BOOLEAN'
ON CONFLICT (flag_id, key) DO NOTHING;

INSERT INTO feature_flag_environment_configs (
    flag_id,
    environment_id,
    enabled,
    default_variant_key,
    off_variant_key,
    rules_json
)
SELECT
    f.id,
    e.id,
    FALSE,
    'enabled',
    'disabled',
    '{"rules":[]}'::jsonb
FROM feature_flags f
JOIN feature_flag_environments e ON e.organization_id = f.organization_id
WHERE f.key = 'moneat.system.native-incident-response'
  AND f.value_type = 'BOOLEAN'
ON CONFLICT (flag_id, environment_id) DO NOTHING;

INSERT INTO feature_flag_audit_events (
    organization_id,
    flag_id,
    event_type,
    after_json
)
SELECT
    f.organization_id,
    f.id,
    'system_flag.initialized',
    '{"key":"moneat.system.native-incident-response","default":"disabled"}'::jsonb
FROM feature_flags f
WHERE f.key = 'moneat.system.native-incident-response'
  AND f.value_type = 'BOOLEAN'
  AND NOT EXISTS (
      SELECT 1
      FROM feature_flag_audit_events a
      WHERE a.organization_id = f.organization_id
        AND a.flag_id = f.id
        AND a.event_type = 'system_flag.initialized'
  );
