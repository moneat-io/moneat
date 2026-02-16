-- V54: Add comprehensive on-call demo data

-- 1. On-call schedules (2 schedules)
INSERT INTO on_call_schedules (
    id, organization_id, name, rotation_type, handoff_time, timezone, created_at, updated_at
)
VALUES 
    (
        1,
        -1,
        'Primary On-Call',
        'WEEKLY',
        '09:00:00',
        'America/Los_Angeles',
        NOW(),
        NOW()
    ),
    (
        2,
        -1,
        'Secondary On-Call',
        'WEEKLY',
        '09:00:00',
        'America/Los_Angeles',
        NOW(),
        NOW()
    )
ON CONFLICT (id) DO NOTHING;

-- 2. On-call participants (demo user is in the rotation)
INSERT INTO on_call_participants (schedule_id, user_id, position, created_at)
VALUES 
    (1, -1, 0, NOW()),  -- Demo user is first in primary rotation
    (2, -1, 0, NOW())   -- Demo user is also in secondary
ON CONFLICT (schedule_id, user_id) DO NOTHING;

-- 3. Escalation policies (2 policies)
INSERT INTO escalation_policies (
    id, organization_id, name, description, repeat_count, created_at, updated_at
)
VALUES 
    (
        1,
        -1,
        'Critical Service Escalation',
        'Escalation for P0/P1 incidents affecting critical services',
        2,
        NOW(),
        NOW()
    ),
    (
        2,
        -1,
        'Standard Escalation',
        'Standard escalation policy for P2-P4 incidents',
        1,
        NOW(),
        NOW()
    )
ON CONFLICT (id) DO NOTHING;

-- 4. Escalation steps
INSERT INTO escalation_steps (escalation_policy_id, step_order, timeout_minutes, created_at)
VALUES 
    -- Critical escalation: Step 0 - Primary on-call (5 min)
    (1, 0, 5, NOW()),
    -- Critical escalation: Step 1 - Secondary on-call (10 min)  
    (1, 1, 10, NOW()),
    -- Critical escalation: Step 2 - All team members (30 min)
    (1, 2, 30, NOW()),
    -- Standard escalation: Step 0 - Primary on-call (15 min)
    (2, 0, 15, NOW()),
    -- Standard escalation: Step 1 - Secondary on-call (30 min)
    (2, 1, 30, NOW())
ON CONFLICT (escalation_policy_id, step_order) DO NOTHING;

-- 5. Escalation step targets
INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
SELECT es.id, 'ON_CALL_SCHEDULE', 1, NOW()
FROM escalation_steps es 
WHERE es.escalation_policy_id = 1 AND es.step_order = 0
ON CONFLICT DO NOTHING;

INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
SELECT es.id, 'ON_CALL_SCHEDULE', 2, NOW()
FROM escalation_steps es 
WHERE es.escalation_policy_id = 1 AND es.step_order = 1
ON CONFLICT DO NOTHING;

INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
SELECT es.id, 'USER', -1, NOW()
FROM escalation_steps es 
WHERE es.escalation_policy_id = 1 AND es.step_order = 2
ON CONFLICT DO NOTHING;

INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
SELECT es.id, 'ON_CALL_SCHEDULE', 1, NOW()
FROM escalation_steps es 
WHERE es.escalation_policy_id = 2 AND es.step_order = 0
ON CONFLICT DO NOTHING;

INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
SELECT es.id, 'ON_CALL_SCHEDULE', 2, NOW()
FROM escalation_steps es 
WHERE es.escalation_policy_id = 2 AND es.step_order = 1
ON CONFLICT DO NOTHING;

-- 6. On-call incidents (major incidents)
INSERT INTO on_call_incidents (
    id, organization_id, title, description, severity, status,
    declared_by, declared_at, resolved_by, resolved_at,
    created_at, updated_at
)
VALUES 
    (
        1,
        -1,
        'Database Performance Degradation',
        'Users experiencing slow response times. Database queries taking 5-10x longer than normal.',
        'P1',
        'RESOLVED',
        -1,
        NOW() - INTERVAL '3 days',
        -1,
        NOW() - INTERVAL '2 days 18 hours',
        NOW() - INTERVAL '3 days',
        NOW() - INTERVAL '2 days 18 hours'
    ),
    (
        2,
        -1,
        'API Server High Error Rate',
        'API error rate spiked to 15%. Investigation ongoing.',
        'P0',
        'RESOLVED',
        -1,
        NOW() - INTERVAL '1 day 6 hours',
        -1,
        NOW() - INTERVAL '1 day 2 hours',
        NOW() - INTERVAL '1 day 6 hours',
        NOW() - INTERVAL '1 day 2 hours'
    ),
    (
        3,
        -1,
        'Elevated Memory Usage',
        'API servers showing 85%+ memory usage. Monitoring for memory leaks.',
        'P2',
        'OPEN',
        -1,
        NOW() - INTERVAL '4 hours',
        NULL,
        NULL,
        NOW() - INTERVAL '4 hours',
        NOW() - INTERVAL '4 hours'
    )
ON CONFLICT (id) DO NOTHING;

-- 7. Incidents (alerts that triggered escalation)
INSERT INTO incidents (
    id, organization_id, escalation_policy_id, title, description,
    priority_level, status, alert_source, deduplication_key,
    triggered_at, acknowledged_at, acknowledged_by, resolved_at, resolved_by,
    incident_id, created_at, updated_at
)
VALUES 
    (
        1,
        -1,
        1,
        'High Database Query Latency',
        'Average query time exceeded 2000ms threshold',
        'P1',
        'RESOLVED',
        'moneat_uptime',
        'db-latency-spike-2026-02-13',
        NOW() - INTERVAL '3 days',
        NOW() - INTERVAL '3 days' + INTERVAL '3 minutes',
        -1,
        NOW() - INTERVAL '2 days 18 hours',
        -1,
        1,
        NOW() - INTERVAL '3 days',
        NOW() - INTERVAL '2 days 18 hours'
    ),
    (
        2,
        -1,
        1,
        'API 5xx Error Rate Critical',
        'API 5xx error rate exceeded 10% threshold',
        'P0',
        'RESOLVED',
        'moneat_uptime',
        'api-5xx-spike-2026-02-15',
        NOW() - INTERVAL '1 day 6 hours',
        NOW() - INTERVAL '1 day 6 hours' + INTERVAL '2 minutes',
        -1,
        NOW() - INTERVAL '1 day 2 hours',
        -1,
        2,
        NOW() - INTERVAL '1 day 6 hours',
        NOW() - INTERVAL '1 day 2 hours'
    ),
    (
        3,
        -1,
        2,
        'High Memory Usage Detected',
        'Server memory usage exceeded 80% threshold',
        'P2',
        'ACKNOWLEDGED',
        'moneat_infrastructure',
        'memory-high-2026-02-16',
        NOW() - INTERVAL '4 hours',
        NOW() - INTERVAL '3 hours 45 minutes',
        -1,
        NULL,
        NULL,
        3,
        NOW() - INTERVAL '4 hours',
        NOW() - INTERVAL '3 hours 45 minutes'
    ),
    (
        4,
        -1,
        2,
        'SSL Certificate Expiring Soon',
        'SSL certificate for api.acmemobile.example.com expires in 14 days',
        'P3',
        'ACKNOWLEDGED',
        'moneat_uptime',
        'ssl-expiry-warning',
        NOW() - INTERVAL '2 days',
        NOW() - INTERVAL '1 day 18 hours',
        -1,
        NULL,
        NULL,
        NULL,
        NOW() - INTERVAL '2 days',
        NOW() - INTERVAL '1 day 18 hours'
    )
ON CONFLICT (id) DO NOTHING;

-- 8. Link alerts to on-call incidents
INSERT INTO on_call_incident_alerts (incident_id, alert_id)
VALUES 
    (1, 1),
    (2, 2),
    (3, 3)
ON CONFLICT (incident_id, alert_id) DO NOTHING;

-- 9. Incident timeline (audit trail)
INSERT INTO incident_timeline (incident_id, event_type, actor_user_id, details, created_at)
VALUES 
    -- Incident 1 timeline
    (1, 'TRIGGERED', NULL, '{"message": "Alert triggered by monitoring system"}', NOW() - INTERVAL '3 days'),
    (1, 'NOTIFICATION_SENT', NULL, '{"targets": ["demo@moneat.dev"], "method": "email"}', NOW() - INTERVAL '3 days' + INTERVAL '10 seconds'),
    (1, 'ACKNOWLEDGED', -1, '{"message": "Investigating database performance"}', NOW() - INTERVAL '3 days' + INTERVAL '3 minutes'),
    (1, 'NOTE_ADDED', -1, '{"note": "Identified slow query in products table. Optimizing indexes."}', NOW() - INTERVAL '2 days 22 hours'),
    (1, 'RESOLVED', -1, '{"message": "Index optimization completed. Query times back to normal."}', NOW() - INTERVAL '2 days 18 hours'),
    
    -- Incident 2 timeline
    (2, 'TRIGGERED', NULL, '{"message": "Critical error rate threshold exceeded"}', NOW() - INTERVAL '1 day 6 hours'),
    (2, 'NOTIFICATION_SENT', NULL, '{"targets": ["demo@moneat.dev"], "method": "email"}', NOW() - INTERVAL '1 day 6 hours' + INTERVAL '5 seconds'),
    (2, 'ACKNOWLEDGED', -1, '{"message": "Checking API server health"}', NOW() - INTERVAL '1 day 6 hours' + INTERVAL '2 minutes'),
    (2, 'NOTE_ADDED', -1, '{"note": "Found memory leak in caching layer. Deploying fix."}', NOW() - INTERVAL '1 day 4 hours'),
    (2, 'RESOLVED', -1, '{"message": "Fix deployed. Error rate back to normal levels."}', NOW() - INTERVAL '1 day 2 hours'),
    
    -- Incident 3 timeline (ongoing)
    (3, 'TRIGGERED', NULL, '{"message": "Memory usage warning triggered"}', NOW() - INTERVAL '4 hours'),
    (3, 'NOTIFICATION_SENT', NULL, '{"targets": ["demo@moneat.dev"], "method": "email"}', NOW() - INTERVAL '4 hours' + INTERVAL '8 seconds'),
    (3, 'ACKNOWLEDGED', -1, '{"message": "Monitoring memory usage. Will restart if needed."}', NOW() - INTERVAL '3 hours 45 minutes'),
    (3, 'NOTE_ADDED', -1, '{"note": "Memory usage stabilized at 82%. Monitoring for next hour."}', NOW() - INTERVAL '2 hours'),
    
    -- Incident 4 timeline
    (4, 'TRIGGERED', NULL, '{"message": "SSL certificate expiry warning"}', NOW() - INTERVAL '2 days'),
    (4, 'NOTIFICATION_SENT', NULL, '{"targets": ["demo@moneat.dev"], "method": "email"}', NOW() - INTERVAL '2 days' + INTERVAL '5 seconds'),
    (4, 'ACKNOWLEDGED', -1, '{"message": "Certificate renewal scheduled"}', NOW() - INTERVAL '1 day 18 hours');
