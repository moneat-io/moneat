-- Demo replay data for mobile apps
-- Creates realistic session replays with multiple segments

-- Helper: Generate replay events (metadata) for each replay
INSERT INTO moneat.replay_events (
    replay_id,
    project_id,
    segment_id,
    timestamp,
    replay_start_timestamp,
    urls,
    error_ids,
    trace_ids,
    environment,
    release,
    platform,
    user_id,
    user_email,
    user_username,
    user_ip_address,
    sdk_name,
    sdk_version,
    browser_name,
    browser_version
)
SELECT
    replayUUIDs.uuid as replay_id,
    if(number % 3 = 0, toUInt64(-1), if(number % 3 = 1, toUInt64(-2), toUInt64(-3))) as project_id,
    0 as segment_id,
    fromUnixTimestamp64Milli(
        toInt64(now64(3)) - toInt64((number * 86400000) + (rand() % 7200000))
    ) as timestamp,
    fromUnixTimestamp64Milli(
        toInt64(now64(3)) - toInt64((number * 86400000) + (rand() % 7200000))
    ) as replay_start_timestamp,
    if(number % 3 = 0, 
        ['HomeActivity', 'ProductListActivity', 'ProductDetailActivity', 'CartActivity'],
        if(number % 3 = 1,
            ['HomeViewController', 'ProductListViewController', 'ProductDetailViewController', 'CartViewController'],
            ['Home', 'ProductList', 'ProductDetail', 'Cart']
        )
    ) as urls,
    if(rand() % 5 = 0, [concat('demo-error-', toString(number))], []) as error_ids,
    [concat('trace-', toString(rand() % 1000))] as trace_ids,
    if(number % 2 = 0, 'production', 'staging') as environment,
    concat('1.0.', toString(number % 10)) as release,
    if(number % 3 = 0, 'android', if(number % 3 = 1, 'cocoa', 'react-native')) as platform,
    concat('demo-user-', toString(number % 20)) as user_id,
    concat('user', toString(number % 20), '@demo.com') as user_email,
    concat('demo_user_', toString(number % 20)) as user_username,
    concat('192.168.1.', toString(number % 255)) as user_ip_address,
    'sentry.mobile' as sdk_name,
    '3.2.1' as sdk_version,
    '' as browser_name,
    '' as browser_version
FROM (
    SELECT
        generateUUIDv4() as uuid,
        number
    FROM numbers(30)
) as replayUUIDs;

-- Helper: Generate replay segments (actual replay data) for each replay
-- Each replay gets 3-5 segments
INSERT INTO moneat.replay_segments (
    replay_id,
    project_id,
    segment_id,
    timestamp,
    recording_data
)
SELECT
    replay_id,
    project_id,
    segment_num as segment_id,
    addSeconds(timestamp, segment_num * 10) as timestamp,
    concat(
        '{"type":"replay_segment","segment_id":', toString(segment_num),
        ',"data":"base64_encoded_replay_data_segment_', toString(segment_num), '"}'
    ) as recording_data
FROM (
    SELECT
        replay_id,
        project_id,
        timestamp,
        arrayJoin(range(toUInt32(rand() % 3 + 3))) as segment_num
    FROM moneat.replay_events
    WHERE toInt64(project_id) IN (-1, -2, -3)
) as segments;
