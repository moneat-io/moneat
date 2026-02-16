-- V53: Add comprehensive demo data for ALL features

-- 1. Uptime Monitors (2 monitors)
INSERT INTO uptime_monitors (
    id, organization_id, name, type, active, url, method, 
    expected_status_codes, created_at
)
VALUES 
    (
        '00000000-0000-0000-0000-000000000001'::uuid,
        -1,
        'API Server',
        'http',
        true,
        'https://api.acmemobile.example.com/health',
        'GET',
        '200',
        NOW()
    ),
    (
        '00000000-0000-0000-0000-000000000002'::uuid,
        -1,
        'Web App',
        'http',
        true,
        'https://app.acmemobile.example.com',
        'GET',
        '200,301',
        NOW()
    )
ON CONFLICT (id) DO NOTHING;

-- 2. Status Page
INSERT INTO status_pages (
    id, organization_id, name, slug, description,
    logo_url, primary_color, show_uptime_history, 
    history_days, is_public, created_at, updated_at
)
VALUES (
    '10000000-0000-0000-0000-000000000001'::uuid,
    -1,
    'Acme Mobile Status',
    'acme-mobile-demo',
    'Real-time status and uptime monitoring for Acme Mobile apps and services',
    'https://via.placeholder.com/150/3B82F6/FFFFFF?text=Acme',
    '#3B82F6',
    true,
    90,
    true,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;

-- 3. Link monitors to status page
INSERT INTO status_page_monitors (status_page_id, monitor_id, display_name, sort_order, created_at)
VALUES 
    (
        '10000000-0000-0000-0000-000000000001'::uuid,
        '00000000-0000-0000-0000-000000000001'::uuid,
        'API Server',
        0,
        NOW()
    ),
    (
        '10000000-0000-0000-0000-000000000001'::uuid,
        '00000000-0000-0000-0000-000000000002'::uuid,
        'Web Application',
        1,
        NOW()
    )
ON CONFLICT (status_page_id, monitor_id) DO NOTHING;

-- 4. Sample incidents (1 resolved, 1 scheduled maintenance)
INSERT INTO status_page_incidents (
    id, status_page_id, title, status, type, impact, 
    resolved_at, created_at, updated_at
)
VALUES 
    (
        '20000000-0000-0000-0000-000000000001'::uuid,
        '10000000-0000-0000-0000-000000000001'::uuid,
        'API Server Intermittent Errors',
        'resolved',
        'incident',
        'minor',
        NOW() - INTERVAL '2 days',
        NOW() - INTERVAL '3 days',
        NOW() - INTERVAL '2 days'
    ),
    (
        '20000000-0000-0000-0000-000000000002'::uuid,
        '10000000-0000-0000-0000-000000000001'::uuid,
        'Scheduled Database Maintenance',
        'scheduled',
        'maintenance',
        'none',
        NULL,
        NOW() - INTERVAL '1 day',
        NOW() - INTERVAL '1 day'
    )
ON CONFLICT (id) DO NOTHING;

-- 5. Incident updates
INSERT INTO status_page_incident_updates (incident_id, status, message, created_at)
VALUES 
    -- Updates for the resolved incident
    (
        '20000000-0000-0000-0000-000000000001'::uuid,
        'investigating',
        'We are investigating reports of intermittent 500 errors on the API server.',
        NOW() - INTERVAL '3 days'
    ),
    (
        '20000000-0000-0000-0000-000000000001'::uuid,
        'identified',
        'We have identified the issue as a memory leak in the caching layer. A fix is being deployed.',
        NOW() - INTERVAL '2 days 12 hours'
    ),
    (
        '20000000-0000-0000-0000-000000000001'::uuid,
        'resolved',
        'The issue has been resolved. All services are operating normally.',
        NOW() - INTERVAL '2 days'
    ),
    -- Update for scheduled maintenance
    (
        '20000000-0000-0000-0000-000000000002'::uuid,
        'scheduled',
        'We will be performing database maintenance on Saturday at 2:00 AM UTC. Expected downtime: 30 minutes.',
        NOW() - INTERVAL '1 day'
    );
