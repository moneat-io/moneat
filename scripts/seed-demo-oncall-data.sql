-- SQL script to add on-call data to demo user
-- Run this after seeding demo data: psql -h localhost -p 5499 -U moneat -d moneat -f scripts/seed-demo-oncall-data.sql

DO $$ 
DECLARE
    demo_user_id INTEGER;
    demo_org_id INTEGER;
    sarah_id INTEGER;
    michael_id INTEGER;
    alex_id INTEGER;
    primary_schedule_id INTEGER;
    secondary_schedule_id INTEGER;
    database_schedule_id INTEGER;
    critical_policy_id INTEGER;
    high_policy_id INTEGER;
    medium_policy_id INTEGER;
    step_id INTEGER;
    business_hours_id INTEGER;
    triggered_incident_id INTEGER;
    acknowledged_incident_id INTEGER;
    resolved_incident_id INTEGER;
BEGIN
    -- Get demo user and org
    SELECT id INTO demo_user_id FROM users WHERE email = 'demo@moneat.dev';
    SELECT organization_id INTO demo_org_id FROM memberships WHERE user_id = demo_user_id LIMIT 1;
    
    IF demo_user_id IS NULL OR demo_org_id IS NULL THEN
        RAISE EXCEPTION 'Demo user not found. Please run seed-demo-data.sh first.';
    END IF;
    
    -- Check if on-call data already exists
    IF EXISTS (SELECT 1 FROM on_call_schedules WHERE organization_id = demo_org_id) THEN
        RAISE NOTICE 'On-call data already exists for demo org. Skipping.';
        RETURN;
    END IF;
    
    RAISE NOTICE 'Adding on-call data to demo organization...';
    
    -- Create team members
    INSERT INTO users (email, password_hash, name, email_verified, onboarding_completed)
    VALUES ('sarah.chen@acme.dev', '$2a$10$x/DEamfW7X03CFkAhKSEVuxDOp.XJt6fViITa8u4psB0MgiwqDCii', 'Sarah Chen', true, true)
    RETURNING id INTO sarah_id;
    
    INSERT INTO users (email, password_hash, name, email_verified, onboarding_completed)
    VALUES ('michael.rodriguez@acme.dev', '$2a$10$x/DEamfW7X03CFkAhKSEVuxDOp.XJt6fViITa8u4psB0MgiwqDCii', 'Michael Rodriguez', true, true)
    RETURNING id INTO michael_id;
    
    INSERT INTO users (email, password_hash, name, email_verified, onboarding_completed)
    VALUES ('alex.kumar@acme.dev', '$2a$10$x/DEamfW7X03CFkAhKSEVuxDOp.XJt6fViITa8u4psB0MgiwqDCii', 'Alex Kumar', true, true)
    RETURNING id INTO alex_id;
    
    -- Add memberships
    INSERT INTO memberships (user_id, organization_id, role) VALUES (sarah_id, demo_org_id, 'admin');
    INSERT INTO memberships (user_id, organization_id, role) VALUES (michael_id, demo_org_id, 'member');
    INSERT INTO memberships (user_id, organization_id, role) VALUES (alex_id, demo_org_id, 'member');
    
    -- Create on-call schedules
    INSERT INTO on_call_schedules (organization_id, name, rotation_type, handoff_time, timezone, created_at, updated_at)
    VALUES (demo_org_id, 'Primary On-Call', 'WEEKLY', '09:00:00', 'America/New_York', NOW(), NOW())
    RETURNING id INTO primary_schedule_id;
    
    INSERT INTO on_call_schedules (organization_id, name, rotation_type, handoff_time, timezone, created_at, updated_at)
    VALUES (demo_org_id, 'Secondary On-Call', 'WEEKLY', '09:00:00', 'America/New_York', NOW(), NOW())
    RETURNING id INTO secondary_schedule_id;
    
    INSERT INTO on_call_schedules (organization_id, name, rotation_type, handoff_time, timezone, created_at, updated_at)
    VALUES (demo_org_id, 'Database Team', 'DAILY', '00:00:00', 'UTC', NOW(), NOW())
    RETURNING id INTO database_schedule_id;
    
    -- Add participants
    INSERT INTO on_call_participants (schedule_id, user_id, position, created_at) VALUES
    (primary_schedule_id, sarah_id, 0, NOW()),
    (primary_schedule_id, michael_id, 1, NOW()),
    (primary_schedule_id, alex_id, 2, NOW()),
    (secondary_schedule_id, demo_user_id, 0, NOW()),
    (database_schedule_id, alex_id, 0, NOW()),
    (database_schedule_id, sarah_id, 1, NOW());
    
    -- Create escalation policies
    INSERT INTO escalation_policies (organization_id, name, description, repeat_count, created_at, updated_at)
    VALUES (demo_org_id, 'Critical Production Alerts', 'Immediate escalation for P0/P1 incidents', 3, NOW(), NOW())
    RETURNING id INTO critical_policy_id;
    
    INSERT INTO escalation_policies (organization_id, name, description, repeat_count, created_at, updated_at)
    VALUES (demo_org_id, 'High Priority Alerts', 'Standard escalation for P2 incidents', 2, NOW(), NOW())
    RETURNING id INTO high_policy_id;
    
    INSERT INTO escalation_policies (organization_id, name, description, repeat_count, created_at, updated_at)
    VALUES (demo_org_id, 'Medium Priority Alerts', 'Business hours escalation for P3 incidents', 1, NOW(), NOW())
    RETURNING id INTO medium_policy_id;
    
    -- Create escalation steps (Critical policy: 3 steps)
    INSERT INTO escalation_steps (escalation_policy_id, step_order, timeout_minutes, created_at)
    VALUES (critical_policy_id, 0, 5, NOW())
    RETURNING id INTO step_id;
    INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
    VALUES (step_id, 'ON_CALL_SCHEDULE', primary_schedule_id, NOW());
    
    INSERT INTO escalation_steps (escalation_policy_id, step_order, timeout_minutes, created_at)
    VALUES (critical_policy_id, 1, 5, NOW())
    RETURNING id INTO step_id;
    INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
    VALUES (step_id, 'ON_CALL_SCHEDULE', secondary_schedule_id, NOW());
    
    INSERT INTO escalation_steps (escalation_policy_id, step_order, timeout_minutes, created_at)
    VALUES (critical_policy_id, 2, 10, NOW())
    RETURNING id INTO step_id;
    INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at) VALUES
    (step_id, 'USER', demo_user_id, NOW()),
    (step_id, 'USER', sarah_id, NOW());
    
    -- High priority policy steps
    INSERT INTO escalation_steps (escalation_policy_id, step_order, timeout_minutes, created_at)
    VALUES (high_policy_id, 0, 10, NOW())
    RETURNING id INTO step_id;
    INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
    VALUES (step_id, 'ON_CALL_SCHEDULE', primary_schedule_id, NOW());
    
    INSERT INTO escalation_steps (escalation_policy_id, step_order, timeout_minutes, created_at)
    VALUES (high_policy_id, 1, 15, NOW())
    RETURNING id INTO step_id;
    INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
    VALUES (step_id, 'USER', sarah_id, NOW());
    
    -- Medium priority policy steps
    INSERT INTO escalation_steps (escalation_policy_id, step_order, timeout_minutes, created_at)
    VALUES (medium_policy_id, 0, 30, NOW())
    RETURNING id INTO step_id;
    INSERT INTO escalation_step_targets (escalation_step_id, target_type, target_id, created_at)
    VALUES (step_id, 'ON_CALL_SCHEDULE', primary_schedule_id, NOW());
    
    -- Create alert priorities
    INSERT INTO alert_priorities (organization_id, severity, priority_level, is_pageable, label, description, created_at, updated_at) VALUES
    (demo_org_id, 'CRITICAL', 'P0', true, 'Critical - Total Outage', 'Complete service outage', NOW(), NOW()),
    (demo_org_id, 'HIGH', 'P1', true, 'High - Partial Outage', 'Partial outage or severe degradation', NOW(), NOW()),
    (demo_org_id, 'MEDIUM', 'P2', true, 'Medium - Degraded Performance', 'Performance degradation', NOW(), NOW()),
    (demo_org_id, 'LOW', 'P3', false, 'Low - Minor Issue', 'Minor issues with workarounds', NOW(), NOW());
    
    -- Create business hours
    INSERT INTO business_hours (organization_id, timezone, enabled, created_at, updated_at)
    VALUES (demo_org_id, 'America/New_York', true, NOW(), NOW())
    RETURNING id INTO business_hours_id;
    
    INSERT INTO business_hours_windows (business_hours_id, day_of_week, start_time, end_time, created_at) VALUES
    (business_hours_id, 1, '09:00:00', '18:00:00', NOW()),
    (business_hours_id, 2, '09:00:00', '18:00:00', NOW()),
    (business_hours_id, 3, '09:00:00', '18:00:00', NOW()),
    (business_hours_id, 4, '09:00:00', '18:00:00', NOW()),
    (business_hours_id, 5, '09:00:00', '18:00:00', NOW());
    
    -- Create sample incidents
    INSERT INTO incidents (organization_id, escalation_policy_id, title, description, priority_level, status,
                          alert_source, deduplication_key, current_step, repeat_iteration, triggered_at,
                          metadata, created_at, updated_at)
    VALUES (demo_org_id, critical_policy_id, 'Database Connection Pool Exhausted', 
            'Connection pool exhausted on prod-db-primary-1. All database connections in use.',
            'P0', 'TRIGGERED', 'Datadog Monitor', 'db-conn-pool-prod-' || EXTRACT(EPOCH FROM NOW())::text,
            0, 0, NOW() - INTERVAL '2 minutes',
            '{"hostname": "prod-db-primary-1", "pool_size": "100/100"}'::jsonb,
            NOW() - INTERVAL '2 minutes', NOW() - INTERVAL '2 minutes')
    RETURNING id INTO triggered_incident_id;
    
    INSERT INTO incident_timeline (incident_id, event_type, actor_user_id, details, created_at) VALUES
    (triggered_incident_id, 'TRIGGERED', NULL, '{"message": "Alert triggered by Datadog monitor"}'::jsonb, NOW() - INTERVAL '2 minutes'),
    (triggered_incident_id, 'NOTIFICATION_SENT', NULL, '{"recipient": "Sarah Chen", "method": "PUSH"}'::jsonb, NOW() - INTERVAL '2 minutes');
    
    INSERT INTO incidents (organization_id, escalation_policy_id, title, description, priority_level, status,
                          alert_source, deduplication_key, current_step, repeat_iteration, triggered_at,
                          acknowledged_at, acknowledged_by, metadata, created_at, updated_at)
    VALUES (demo_org_id, critical_policy_id, 'API Response Time SLA Breach', 
            'The /api/v1/checkout endpoint is experiencing severe latency. P95: 8.3s (SLA: 500ms).',
            'P1', 'ACKNOWLEDGED', 'New Relic APM', 'api-timeout-checkout-' || EXTRACT(EPOCH FROM NOW())::text,
            1, 0, NOW() - INTERVAL '35 minutes', NOW() - INTERVAL '28 minutes', sarah_id,
            '{"endpoint": "/api/v1/checkout", "p95_latency": "8300ms"}'::jsonb,
            NOW() - INTERVAL '35 minutes', NOW() - INTERVAL '28 minutes')
    RETURNING id INTO acknowledged_incident_id;
    
    INSERT INTO incident_timeline (incident_id, event_type, actor_user_id, details, created_at) VALUES
    (acknowledged_incident_id, 'TRIGGERED', NULL, '{"message": "Alert triggered by New Relic APM"}'::jsonb, NOW() - INTERVAL '35 minutes'),
    (acknowledged_incident_id, 'ACKNOWLEDGED', sarah_id, '{"message": "Investigating. Likely related to Redis cache config."}'::jsonb, NOW() - INTERVAL '28 minutes');
    
    INSERT INTO incidents (organization_id, escalation_policy_id, title, description, priority_level, status,
                          alert_source, deduplication_key, current_step, repeat_iteration, triggered_at,
                          acknowledged_at, acknowledged_by, resolved_at, resolved_by, metadata, created_at, updated_at)
    VALUES (demo_org_id, high_policy_id, 'Disk Space Warning - Log Server', 
            'Disk usage at 87% on log-server-1. Log rotation not working correctly.',
            'P2', 'RESOLVED', 'Prometheus', 'disk-space-log-' || EXTRACT(EPOCH FROM NOW())::text,
            0, 0, NOW() - INTERVAL '2 days 1 hour', NOW() - INTERVAL '2 days 45 minutes', michael_id,
            NOW() - INTERVAL '2 days', michael_id,
            '{"hostname": "log-server-1", "disk_usage": "87%"}'::jsonb,
            NOW() - INTERVAL '2 days 1 hour', NOW() - INTERVAL '2 days')
    RETURNING id INTO resolved_incident_id;
    
    INSERT INTO incident_timeline (incident_id, event_type, actor_user_id, details, created_at) VALUES
    (resolved_incident_id, 'TRIGGERED', NULL, '{"message": "Disk usage threshold exceeded"}'::jsonb, NOW() - INTERVAL '2 days 1 hour'),
    (resolved_incident_id, 'ACKNOWLEDGED', michael_id, '{"message": "Checking log rotation cron job."}'::jsonb, NOW() - INTERVAL '2 days 45 minutes'),
    (resolved_incident_id, 'RESOLVED', michael_id, '{"message": "Fixed log rotation. Disk usage normal."}'::jsonb, NOW() - INTERVAL '2 days');
    
    RAISE NOTICE '✅ On-call data added successfully!';
    RAISE NOTICE 'Team members: Sarah Chen, Michael Rodriguez, Alex Kumar';
    RAISE NOTICE 'Schedules: 3 (Primary, Secondary, Database Team)';
    RAISE NOTICE 'Escalation policies: 3 (Critical, High, Medium)';
    RAISE NOTICE 'Sample incidents: 3 (1 triggered, 1 acknowledged, 1 resolved)';
END $$;
