-- V7: Seed comprehensive demo data for ALL features
-- This seeds sessions, spans, logs, feedback, and uptime data for demo mode

-- Sessions (100 sessions across 3 projects over past 7 days)
-- Varying statuses: mostly 'ok', some 'exited', few 'crashed'
INSERT INTO moneat.sessions (session_id, project_id, started, duration_ms, status, errors, release, environment, user_id, received_at)
SELECT
    generateUUIDv4() as session_id,
    CASE number % 3
        WHEN 0 THEN toUInt64(-1)  -- Android
        WHEN 1 THEN toUInt64(-2)  -- iOS  
        ELSE toUInt64(-3)         -- React Native
    END as project_id,
    now() - INTERVAL (number * 2) HOUR as started,
    1000 + (number * 123) % 300000 as duration_ms,  -- 1s to 5min
    CASE 
        WHEN number % 20 = 0 THEN 'crashed'
        WHEN number % 10 = 0 THEN 'exited'
        WHEN number % 8 = 0 THEN 'abnormal'
        ELSE 'ok'
    END as status,
    CASE 
        WHEN number % 20 = 0 THEN (number % 3) + 1
        ELSE 0
    END as errors,
    CASE number % 3
        WHEN 0 THEN '1.3.0'
        WHEN 1 THEN '1.2.1'
        ELSE '1.2.1'
    END as release,
    CASE number % 4
        WHEN 0 THEN 'production'
        WHEN 1 THEN 'staging'
        WHEN 2 THEN 'development'
        ELSE 'production'
    END as environment,
    concat('demo-user-', toString(number % 50)) as user_id,
    now() - INTERVAL (number * 2) HOUR as received_at
FROM numbers(100);

-- Spans (1000 spans for performance monitoring)
-- Realistic operations: http.client, db.query, file.read, etc.
INSERT INTO moneat.spans (span_id, parent_span_id, trace_id, transaction_id, project_id, op, description, start_timestamp, end_timestamp, duration_ms, status, tags, data)
SELECT
    concat('span-', toString(number)) as span_id,
    CASE WHEN number % 10 != 0 THEN concat('span-', toString(floor(number / 10) * 10)) ELSE '' END as parent_span_id,
    concat('trace-', toString(floor(number / 10))) as trace_id,
    generateUUIDv4() as transaction_id,
    CASE number % 3
        WHEN 0 THEN toUInt64(-1)
        WHEN 1 THEN toUInt64(-2)
        ELSE toUInt64(-3)
    END as project_id,
    CASE number % 8
        WHEN 0 THEN 'http.client'
        WHEN 1 THEN 'db.query'
        WHEN 2 THEN 'file.read'
        WHEN 3 THEN 'cache.get'
        WHEN 4 THEN 'function'
        WHEN 5 THEN 'ui.render'
        WHEN 6 THEN 'navigation.push'
        ELSE 'http.server'
    END as op,
    CASE number % 8
        WHEN 0 THEN concat('GET /api/products/', toString(number % 100))
        WHEN 1 THEN concat('SELECT * FROM products WHERE id = ', toString(number % 100))
        WHEN 2 THEN 'Read product images'
        WHEN 3 THEN 'Get cached user data'
        WHEN 4 THEN 'calculateTotal()'
        WHEN 5 THEN 'Render ProductList'
        WHEN 6 THEN 'Navigate to CheckoutScreen'
        ELSE 'POST /api/checkout'
    END as description,
    now() - INTERVAL (number * 30) MINUTE as start_timestamp,
    now() - INTERVAL (number * 30) MINUTE + INTERVAL (10 + (number * 7) % 500) MILLISECOND as end_timestamp,
    (10 + (number * 7) % 500) as duration_ms,
    CASE WHEN number % 50 = 0 THEN 'error' ELSE 'ok' END as status,
    map('environment', 'production', 'user.id', concat('demo-user-', toString(number % 50))) as tags,
    '{}' as data
FROM numbers(1000);

-- Logs (2000 log entries with varying levels)
INSERT INTO moneat.logs (log_id, project_id, timestamp, level, message, body, service, environment, host, source, trace_id, span_id, tags, resource_attributes)
SELECT
    generateUUIDv4() as log_id,
    CASE number % 3
        WHEN 0 THEN toUInt64(-1)
        WHEN 1 THEN toUInt64(-2)
        ELSE toUInt64(-3)
    END as project_id,
    now() - INTERVAL (number * 15) MINUTE as timestamp,
    CASE 
        WHEN number % 50 = 0 THEN 'error'
        WHEN number % 20 = 0 THEN 'warn'
        WHEN number % 5 = 0 THEN 'debug'
        ELSE 'info'
    END as level,
    CASE number % 10
        WHEN 0 THEN concat('User logged in: demo-user-', toString(number % 50))
        WHEN 1 THEN concat('Product viewed: ', toString(number % 100))
        WHEN 2 THEN 'Cart updated'
        WHEN 3 THEN 'Checkout initiated'
        WHEN 4 THEN concat('Payment processed: $', toString((number % 50) + 10), '.99')
        WHEN 5 THEN 'Order confirmed'
        WHEN 6 THEN concat('API request: GET /products/', toString(number % 100))
        WHEN 7 THEN 'Cache miss'
        WHEN 8 THEN 'Database query completed'
        ELSE concat('Error: Failed to load resource id=', toString(number % 100))
    END as message,
    '{"additional": "context"}' as body,
    CASE number % 3
        WHEN 0 THEN 'android-app'
        WHEN 1 THEN 'ios-app'
        ELSE 'react-native-app'
    END as service,
    'production' as environment,
    concat('app-server-', toString((number % 5) + 1)) as host,
    'sdk' as source,
    concat('trace-', toString(number % 100)) as trace_id,
    concat('span-', toString(number % 1000)) as span_id,
    map('user.id', concat('demo-user-', toString(number % 50))) as tags,
    map('service.name', 'shopping-app', 'service.version', '1.3.0') as resource_attributes
FROM numbers(2000);

-- User Feedback (55 feedback items)
INSERT INTO moneat.user_feedback (feedback_id, project_id, timestamp, message, contact_email, name, url, associated_event_id, environment, release, platform, user_id, user_email, user_username, sdk_name, sdk_version, tags)
SELECT
    generateUUIDv4() as feedback_id,
    CASE number % 3
        WHEN 0 THEN toUInt64(-1)
        WHEN 1 THEN toUInt64(-2)
        ELSE toUInt64(-3)
    END as project_id,
    now() - INTERVAL (number * 3) HOUR as timestamp,
    CASE number % 10
        WHEN 0 THEN 'App crashed when I tried to checkout'
        WHEN 1 THEN 'Payment failed but I was charged'
        WHEN 2 THEN 'Images not loading on product page'
        WHEN 3 THEN 'Search results are very slow'
        WHEN 4 THEN 'Love the new UI! Very smooth'
        WHEN 5 THEN 'Cart items disappear randomly'
        WHEN 6 THEN 'Cannot login with Google'
        WHEN 7 THEN 'Wishlist feature is broken'
        WHEN 8 THEN 'App freezes on order history page'
        ELSE 'Great app overall, minor bugs'
    END as message,
    concat('demo-user-', toString(number % 50), '@example.com') as contact_email,
    concat('Demo User ', toString(number % 50)) as name,
    '/checkout' as url,
    CASE WHEN number % 3 = 0 THEN concat('demo-event-', toString(number)) ELSE '' END as associated_event_id,
    'production' as environment,
    '1.3.0' as release,
    CASE number % 3
        WHEN 0 THEN 'android'
        WHEN 1 THEN 'cocoa'
        ELSE 'javascript'
    END as platform,
    concat('demo-user-', toString(number % 50)) as user_id,
    concat('demo-user-', toString(number % 50), '@example.com') as user_email,
    concat('demouser', toString(number % 50)) as user_username,
    'sentry.java.android' as sdk_name,
    '6.0.0' as sdk_version,
    map('priority', CASE WHEN number % 5 = 0 THEN 'high' ELSE 'normal' END) as tags
FROM numbers(55);

-- Uptime Heartbeats (4000 heartbeats for 2 monitors over 7 days = ~12 checks/hour)
-- Monitor 1: API Server (99% uptime)
-- Monitor 2: Web App (98% uptime)
INSERT INTO moneat.uptime_heartbeats (monitor_id, timestamp, status, response_time_ms, status_code, message, ping_ms)
SELECT
    if(number % 2 = 0, toUUID('00000000-0000-0000-0000-000000000001'), toUUID('00000000-0000-0000-0000-000000000002')) as monitor_id,
    now() - INTERVAL (number * 5) MINUTE as timestamp,
    if(number % 100 = 0, 0, 1) as status,
    if(number % 100 = 0, -1, 50 + (number * 13) % 200) as response_time_ms,
    if(number % 100 = 0, 500 + (number % 4) * 3, 200) as status_code,
    if(number % 100 = 0, 'Connection timeout', 'OK') as message,
    5.0 + (number * 3.7) % 15.0 as ping_ms
FROM numbers(4000);
