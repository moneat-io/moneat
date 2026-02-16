-- Seed demo data for demo user (project IDs: -1, -2, -3)
-- This creates realistic error events for the demo account
-- Demo user credentials: demo@moneat.dev / demo123
-- Note: issues table is auto-populated via materialized view from events
-- Note: PostgreSQL uses negative project IDs (-1, -2, -3). ClickHouse UInt64 auto-converts via toUInt64().

-- Insert sample events for demo issues
-- Android events - NullPointerException
INSERT INTO events (
    event_id, project_id, issue_id, timestamp, received_at, event_type,
    platform, level, message, exception_type, exception_value,
    stack_trace, environment, release, user_id, user_email,
    device_model, os_name, os_version, fingerprint
)
SELECT
    generateUUIDv4(),
    toUInt64(-1),
    'demo-issue-android-1',
    now() - INTERVAL (number % 168) HOUR,
    now() - INTERVAL (number % 168) HOUR,
    'error',
    'android',
    'error',
    'Attempt to invoke virtual method on a null object reference',
    'java.lang.NullPointerException',
    'Attempt to invoke virtual method ''java.lang.String com.acme.Product.getName()'' on a null object reference',
    'at com.acme.shopping.ui.ProductDetailFragment.updateUI(ProductDetailFragment.kt:87)\nat com.acme.shopping.ui.ProductDetailFragment.onViewCreated(ProductDetailFragment.kt:45)\nat androidx.fragment.app.Fragment.performViewCreated(Fragment.java:2987)',
    'production',
    '1.3.0',
    toString(1000 + (number % 100)),
    concat('user', toString(number % 89), '@example.com'),
    arrayElement(['Samsung Galaxy S23', 'Google Pixel 8', 'OnePlus 11'], number % 3 + 1),
    'Android',
    arrayElement(['14', '13', '12'], number % 3 + 1),
    ['NullPointerException', 'ProductDetailFragment', 'updateUI']
FROM numbers(50);

-- iOS events - NSInvalidArgumentException
INSERT INTO events (
    event_id, project_id, issue_id, timestamp, received_at, event_type,
    platform, level, message, exception_type, exception_value,
    stack_trace, environment, release, user_id, user_email,
    device_model, os_name, os_version, fingerprint
)
SELECT
    generateUUIDv4(),
    toUInt64(-2),
    'demo-issue-ios-1',
    now() - INTERVAL (number % 168) HOUR,
    now() - INTERVAL (number % 168) HOUR,
    'error',
    'ios',
    'error',
    'Invalid argument passed to method',
    'NSInvalidArgumentException',
    '*** -[__NSArrayM objectAtIndexedSubscript:]: index 5 beyond bounds [0 .. 2]',
    'ProductViewModel.updateProduct(_:) (ProductViewModel.swift:45)\nProductDetailView.body.getter (ProductDetailView.swift:78)\nswift::runtime::execute (swift_runtime:234)',
    'production',
    '1.3.0',
    toString(2000 + (number % 100)),
    concat('user', toString(number % 76), '@example.com'),
    arrayElement(['iPhone 15 Pro', 'iPhone 14', 'iPhone 13'], number % 3 + 1),
    'iOS',
    arrayElement(['17.3', '17.2', '16.5'], number % 3 + 1),
    ['NSInvalidArgumentException', 'ProductViewModel', 'updateProduct']
FROM numbers(40);

-- React Native events - TypeError
INSERT INTO events (
    event_id, project_id, issue_id, timestamp, received_at, event_type,
    platform, level, message, exception_type, exception_value,
    stack_trace, environment, release, user_id, user_email,
    device_model, os_name, os_version, fingerprint
)
SELECT
    generateUUIDv4(),
    toUInt64(-3),
    'demo-issue-rn-1',
    now() - INTERVAL (number % 192) HOUR,
    now() - INTERVAL (number % 192) HOUR,
    'error',
    'react-native',
    'error',
    'Cannot read property of undefined',
    'TypeError',
    'Cannot read property ''getName'' of undefined',
    'at ProductDetail.render (ProductDetail.js:23:15)\nat ReactCompositeComponent.render (react-reconciler.js:1456)\nat updateComponent (react-reconciler.js:2341)',
    'production',
    '1.2.1',
    toString(3000 + (number % 100)),
    concat('user', toString(number % 84), '@example.com'),
    arrayElement(['iPhone 15', 'Samsung Galaxy S23', 'Google Pixel 8'], number % 3 + 1),
    arrayElement(['iOS', 'Android'], number % 2 + 1),
    arrayElement(['17.3', '14', '13'], number % 3 + 1),
    ['TypeError', 'ProductDetail', 'undefined']
FROM numbers(45);

-- Add some additional events for other issues to make them more realistic
-- Android OutOfMemoryError
INSERT INTO events (
    event_id, project_id, issue_id, timestamp, received_at, event_type,
    platform, level, message, exception_type, exception_value,
    stack_trace, environment, release, user_id, user_email,
    device_model, os_name, os_version, fingerprint
)
SELECT
    generateUUIDv4(),
    toUInt64(-1),
    'demo-issue-android-2',
    now() - INTERVAL (number % 144) HOUR,
    now() - INTERVAL (number % 144) HOUR,
    'error',
    'android',
    'fatal',
    'Failed to allocate memory for bitmap',
    'java.lang.OutOfMemoryError',
    'Failed to allocate a 8294400 byte allocation with 4194304 free bytes',
    'at android.graphics.BitmapFactory.nativeDecodeStream(Native Method)\nat com.acme.shopping.util.ImageLoader.loadBitmap(ImageLoader.kt:34)',
    'production',
    arrayElement(['1.2.0', '1.2.1', '1.3.0'], number % 3 + 1),
    toString(1000 + (number % 100)),
    concat('user', toString(number % 50), '@example.com'),
    arrayElement(['Samsung Galaxy S23', 'Google Pixel 8'], number % 2 + 1),
    'Android',
    arrayElement(['14', '13'], number % 2 + 1),
    ['OutOfMemoryError', 'ImageLoader', 'loadBitmap']
FROM numbers(30);

-- iOS additional events - EXC_BAD_ACCESS
INSERT INTO events (
    event_id, project_id, issue_id, timestamp, received_at, event_type,
    platform, level, message, exception_type, exception_value,
    stack_trace, environment, release, user_id, user_email,
    device_model, os_name, os_version, fingerprint
)
SELECT
    generateUUIDv4(),
    toUInt64(-2),
    'demo-issue-ios-2',
    now() - INTERVAL (number % 120) HOUR,
    now() - INTERVAL (number % 120) HOUR,
    'error',
    'ios',
    'fatal',
    'Bad memory access',
    'EXC_BAD_ACCESS',
    'Attempted to dereference garbage pointer',
    'ImageCache.loadImage(url:) (ImageCache.swift:67)\nProductView.loadProductImage (ProductView.swift:123)',
    'production',
    arrayElement(['1.2.0', '1.2.1', '1.3.0'], number % 3 + 1),
    toString(2000 + (number % 100)),
    concat('user', toString(number % 50), '@example.com'),
    arrayElement(['iPhone 15 Pro', 'iPhone 14'], number % 2 + 1),
    'iOS',
    arrayElement(['17.3', '17.2'], number % 2 + 1),
    ['EXC_BAD_ACCESS', 'ImageCache', 'loadImage']
FROM numbers(25);

-- React Native additional events - ReferenceError
INSERT INTO events (
    event_id, project_id, issue_id, timestamp, received_at, event_type,
    platform, level, message, exception_type, exception_value,
    stack_trace, environment, release, user_id, user_email,
    device_model, os_name, os_version, fingerprint
)
SELECT
    generateUUIDv4(),
    toUInt64(-3),
    'demo-issue-rn-2',
    now() - INTERVAL (number % 120) HOUR,
    now() - INTERVAL (number % 120) HOUR,
    'error',
    'react-native',
    'error',
    'Variable not defined',
    'ReferenceError',
    'cartTotal is not defined',
    'at CartContext.updateTotal (CartContext.js:34)\nat CartView.componentDidUpdate (CartView.js:89)',
    'production',
    arrayElement(['1.2.0', '1.2.1'], number % 2 + 1),
    toString(3000 + (number % 100)),
    concat('user', toString(number % 50), '@example.com'),
    arrayElement(['iPhone 15', 'Samsung Galaxy S23'], number % 2 + 1),
    arrayElement(['iOS', 'Android'], number % 2 + 1),
    arrayElement(['17.3', '14'], number % 2 + 1),
    ['ReferenceError', 'CartContext', 'undefined']
FROM numbers(20);
