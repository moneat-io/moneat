-- Seed essential demo data: releases and uptime monitors

-- ==============================================
-- RELEASES
-- ==============================================
INSERT INTO releases (project_id, version, created_at)
SELECT -1, '1.3.0', extract(epoch from (CURRENT_TIMESTAMP - INTERVAL '7 days')) * 1000
WHERE NOT EXISTS (SELECT 1 FROM releases WHERE project_id = -1 AND version = '1.3.0')
UNION ALL
SELECT -1, '1.2.1', extract(epoch from (CURRENT_TIMESTAMP - INTERVAL '21 days')) * 1000
WHERE NOT EXISTS (SELECT 1 FROM releases WHERE project_id = -1 AND version = '1.2.1')
UNION ALL
SELECT -2, '1.3.0', extract(epoch from (CURRENT_TIMESTAMP - INTERVAL '7 days')) * 1000
WHERE NOT EXISTS (SELECT 1 FROM releases WHERE project_id = -2 AND version = '1.3.0')
UNION ALL
SELECT -3, '1.2.1', extract(epoch from (CURRENT_TIMESTAMP - INTERVAL '14 days')) * 1000
WHERE NOT EXISTS (SELECT 1 FROM releases WHERE project_id = -3 AND version = '1.2.1');

-- ==============================================
-- UPTIME MONITORS
-- ==============================================
INSERT INTO uptime_monitors (id, organization_id, name, type, url, method, interval_seconds, timeout_seconds, created_at)
SELECT 
    'aaaaaaaa-1111-0000-0000-000000000001'::uuid, 
    -1, 
    'API Server', 
    'http',
    'https://api.acme-mobile.com/health',
    'GET',
    60,
    30,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM uptime_monitors WHERE id = 'aaaaaaaa-1111-0000-0000-000000000001'::uuid)
UNION ALL
SELECT 
    'aaaaaaaa-1111-0000-0000-000000000002'::uuid,
    -1,
    'Web App',
    'http',
    'https://app.acme-mobile.com',
    'GET',
    120,
    30,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM uptime_monitors WHERE id = 'aaaaaaaa-1111-0000-0000-000000000002'::uuid);
