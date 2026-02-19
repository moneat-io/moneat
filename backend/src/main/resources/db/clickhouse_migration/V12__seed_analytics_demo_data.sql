-- Seed analytics demo data for demo projects (-1, -2, -3).
-- Generates ~30 days of realistic web-analytics pageviews and custom events.

ALTER TABLE analytics_events
DELETE WHERE project_id IN (toUInt64(-1), toUInt64(-2), toUInt64(-3));

ALTER TABLE analytics_sessions_hourly
DELETE WHERE project_id IN (toUInt64(-1), toUInt64(-2), toUInt64(-3));

INSERT INTO analytics_events (
    event_id,
    project_id,
    session_id,
    event_name,
    hostname,
    pathname,
    referrer,
    referrer_source,
    utm_source,
    utm_medium,
    utm_campaign,
    country_code,
    browser,
    browser_version,
    os,
    device_type,
    screen_width,
    props,
    timestamp
)
SELECT
    generateUUIDv4() AS event_id,

    -- Distribute across demo projects
    CASE intDiv(number, 5) % 3
        WHEN 0 THEN toUInt64(-1)
        WHEN 1 THEN toUInt64(-2)
        ELSE toUInt64(-3)
    END AS project_id,

    -- ~500 unique sessions (same session gets multiple pageviews)
    concat('sess-', toString(intDiv(number, 5) % 500)) AS session_id,

    -- 85% pageviews, 15% custom events
    CASE
        WHEN number % 20 < 17 THEN 'pageview'
        WHEN number % 20 = 17 THEN 'signup_click'
        WHEN number % 20 = 18 THEN 'add_to_cart'
        ELSE 'purchase'
    END AS event_name,

    'demo.moneat.io' AS hostname,

    -- Realistic page paths
    CASE number % 12
        WHEN 0  THEN '/'
        WHEN 1  THEN '/'
        WHEN 2  THEN '/'
        WHEN 3  THEN '/pricing'
        WHEN 4  THEN '/docs'
        WHEN 5  THEN '/docs/getting-started'
        WHEN 6  THEN '/blog'
        WHEN 7  THEN '/blog/why-moneat'
        WHEN 8  THEN '/features'
        WHEN 9  THEN '/login'
        WHEN 10 THEN '/signup'
        ELSE '/about'
    END AS pathname,

    -- Referrer (~40% direct, rest from various sources)
    CASE intDiv(number, 5) % 10
        WHEN 0 THEN ''
        WHEN 1 THEN ''
        WHEN 2 THEN ''
        WHEN 3 THEN ''
        WHEN 4 THEN 'https://www.google.com/'
        WHEN 5 THEN 'https://www.google.com/'
        WHEN 6 THEN 'https://github.com/'
        WHEN 7 THEN 'https://news.ycombinator.com/'
        WHEN 8 THEN 'https://twitter.com/'
        ELSE 'https://dev.to/'
    END AS referrer,

    CASE intDiv(number, 5) % 10
        WHEN 0 THEN 'Direct'
        WHEN 1 THEN 'Direct'
        WHEN 2 THEN 'Direct'
        WHEN 3 THEN 'Direct'
        WHEN 4 THEN 'Google'
        WHEN 5 THEN 'Google'
        WHEN 6 THEN 'GitHub'
        WHEN 7 THEN 'Hacker News'
        WHEN 8 THEN 'Twitter'
        ELSE 'Dev.to'
    END AS referrer_source,

    -- UTM params (only on ~20% of traffic)
    CASE intDiv(number, 5) % 15
        WHEN 0 THEN 'newsletter'
        WHEN 1 THEN 'producthunt'
        ELSE ''
    END AS utm_source,

    CASE intDiv(number, 5) % 15
        WHEN 0 THEN 'email'
        WHEN 1 THEN 'social'
        ELSE ''
    END AS utm_medium,

    CASE intDiv(number, 5) % 15
        WHEN 0 THEN 'feb-launch'
        WHEN 1 THEN 'ph-launch'
        ELSE ''
    END AS utm_campaign,

    -- Country distribution
    CASE intDiv(number, 5) % 12
        WHEN 0  THEN 'US'
        WHEN 1  THEN 'US'
        WHEN 2  THEN 'US'
        WHEN 3  THEN 'GB'
        WHEN 4  THEN 'DE'
        WHEN 5  THEN 'FR'
        WHEN 6  THEN 'CA'
        WHEN 7  THEN 'AU'
        WHEN 8  THEN 'IN'
        WHEN 9  THEN 'BR'
        WHEN 10 THEN 'JP'
        ELSE 'NL'
    END AS country_code,

    -- Browser mix
    CASE intDiv(number, 5) % 8
        WHEN 0 THEN 'Chrome'
        WHEN 1 THEN 'Chrome'
        WHEN 2 THEN 'Chrome'
        WHEN 3 THEN 'Firefox'
        WHEN 4 THEN 'Safari'
        WHEN 5 THEN 'Safari'
        WHEN 6 THEN 'Edge'
        ELSE 'Arc'
    END AS browser,

    CASE intDiv(number, 5) % 8
        WHEN 0 THEN '121.0'
        WHEN 1 THEN '120.0'
        WHEN 2 THEN '119.0'
        WHEN 3 THEN '122.0'
        WHEN 4 THEN '17.3'
        WHEN 5 THEN '17.2'
        WHEN 6 THEN '121.0'
        ELSE '1.0'
    END AS browser_version,

    -- OS mix
    CASE intDiv(number, 5) % 6
        WHEN 0 THEN 'macOS'
        WHEN 1 THEN 'Windows'
        WHEN 2 THEN 'Windows'
        WHEN 3 THEN 'Linux'
        WHEN 4 THEN 'iOS'
        ELSE 'Android'
    END AS os,

    -- Device type
    CASE intDiv(number, 5) % 6
        WHEN 4 THEN 'Mobile'
        WHEN 5 THEN 'Mobile'
        ELSE 'Desktop'
    END AS device_type,

    CASE intDiv(number, 5) % 6
        WHEN 4 THEN toUInt16(390)
        WHEN 5 THEN toUInt16(412)
        ELSE toUInt16(1440 + (intDiv(number, 5) % 3) * 80)
    END AS screen_width,

    -- Custom properties for non-pageview events
    CASE
        WHEN number % 20 = 18 THEN map('plan', CASE number % 3 WHEN 0 THEN 'pro' WHEN 1 THEN 'team' ELSE 'enterprise' END)
        WHEN number % 20 = 19 THEN map('value', toString(29 + (number % 5) * 20))
        ELSE map()
    END AS props,

    -- Spread across last 30 days with hourly variation
    now64(3) - INTERVAL (intDiv(number, 8) % 720) HOUR
             - INTERVAL (number % 3600) SECOND AS timestamp

FROM numbers(3000);
