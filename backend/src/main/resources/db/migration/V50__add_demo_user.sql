-- Demo user, organization, and projects for demo mode
-- These use negative IDs to avoid conflicts with real user data

-- Insert demo user with fixed ID (-1)
-- Note: V45 migration changed email constraint to case-insensitive (users_email_lower_unique)
INSERT INTO users (id, email, password_hash, name, email_verified, onboarding_completed, created_at)
SELECT
    -1,
    'demo@moneat.dev',
    -- Password: demo123 (bcrypt hash)
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'Demo User',
    true,
    true,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE LOWER(email) = LOWER('demo@moneat.dev')
);

-- Insert demo organization with fixed ID (-1)
INSERT INTO organizations (id, name, slug, company_size, created_at)
SELECT
    -1,
    'Acme Mobile Inc',
    'acme-mobile-demo',
    '11-50',
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM organizations WHERE slug = 'acme-mobile-demo'
);

-- Insert demo membership
INSERT INTO memberships (user_id, organization_id, role, created_at)
SELECT -1, -1, 'owner', CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM memberships WHERE user_id = -1 AND organization_id = -1
);

-- Insert demo subscription (PRO plan to avoid upgrade prompts in screenshots)
INSERT INTO subscriptions (
    organization_id,
    plan,
    status,
    billing_interval,
    current_period_start,
    current_period_end,
    payg_budget_cents,
    payg_used_units,
    payg_used_micros,
    pending_meter_units,
    created_at
)
SELECT
    -1,
    'pro',
    'active',
    'monthly',
    NOW(),
    NOW() + INTERVAL '30 days',
    0,
    0,
    0,
    0,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM subscriptions WHERE organization_id = -1
);

-- Insert demo projects with fixed IDs (-1, -2, -3)
INSERT INTO projects (id, organization_id, name, slug, framework, created_at)
SELECT -1, -1, 'Android Shopping App', 'android-shopping', 'android', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM projects WHERE organization_id = -1 AND slug = 'android-shopping')
UNION ALL
SELECT -2, -1, 'iOS Shopping App', 'ios-shopping', 'ios', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM projects WHERE organization_id = -1 AND slug = 'ios-shopping')
UNION ALL
SELECT -3, -1, 'React Native App', 'react-native-app', 'react-native', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM projects WHERE organization_id = -1 AND slug = 'react-native-app');

-- Insert demo project keys with deterministic public keys
INSERT INTO project_keys (project_id, public_key, secret_key, name, is_active, created_at)
SELECT -1, 'demo_android_public_key_fixed', 'demo_android_secret_key_fixed', 'Default Key', true, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM project_keys WHERE public_key = 'demo_android_public_key_fixed')
UNION ALL
SELECT -2, 'demo_ios_public_key_fixed', 'demo_ios_secret_key_fixed', 'Default Key', true, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM project_keys WHERE public_key = 'demo_ios_public_key_fixed')
UNION ALL
SELECT -3, 'demo_reactnative_public_key_fixed', 'demo_reactnative_secret_key_fixed', 'Default Key', true, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM project_keys WHERE public_key = 'demo_reactnative_public_key_fixed');

-- Note: The bcrypt hash above is for password "demo123"
-- Generated with BCrypt.hashpw("demo123", BCrypt.gensalt(10))
