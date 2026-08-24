-- Native incident response is available by default for entitled organizations.
-- Keep the historical rollout migration immutable; remove its reserved flag and
-- dependent configurations while preserving feature-flag audit history. Audit
-- rows intentionally retain their event payload but no longer point to a
-- deleted flag.
UPDATE feature_flag_audit_events
SET flag_id = NULL
WHERE flag_id IN (
    SELECT id
    FROM feature_flags
    WHERE key = 'moneat.system.native-incident-response'
);

DELETE FROM feature_flag_environment_configs
WHERE flag_id IN (
    SELECT id
    FROM feature_flags
    WHERE key = 'moneat.system.native-incident-response'
);

DELETE FROM feature_flag_variants
WHERE flag_id IN (
    SELECT id
    FROM feature_flags
    WHERE key = 'moneat.system.native-incident-response'
);

DELETE FROM feature_flags
WHERE key = 'moneat.system.native-incident-response';
