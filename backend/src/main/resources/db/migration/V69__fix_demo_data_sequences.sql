-- V69: Reset sequences after V54 demo data inserted rows with explicit IDs.
-- Without this, SERIAL sequences generate IDs that collide with demo data,
-- causing "duplicate key" errors on the first real insert.

SELECT setval(pg_get_serial_sequence('incidents', 'id'),
       COALESCE((SELECT MAX(id) FROM incidents), 1));

SELECT setval(pg_get_serial_sequence('on_call_schedules', 'id'),
       COALESCE((SELECT MAX(id) FROM on_call_schedules), 1));

SELECT setval(pg_get_serial_sequence('escalation_policies', 'id'),
       COALESCE((SELECT MAX(id) FROM escalation_policies), 1));

SELECT setval(pg_get_serial_sequence('escalation_steps', 'id'),
       COALESCE((SELECT MAX(id) FROM escalation_steps), 1));

SELECT setval(pg_get_serial_sequence('escalation_step_targets', 'id'),
       COALESCE((SELECT MAX(id) FROM escalation_step_targets), 1));

SELECT setval(pg_get_serial_sequence('on_call_incidents', 'id'),
       COALESCE((SELECT MAX(id) FROM on_call_incidents), 1));

SELECT setval(pg_get_serial_sequence('incident_timeline', 'id'),
       COALESCE((SELECT MAX(id) FROM incident_timeline), 1));
