-- Add onboarding completion field to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS onboarding_completed BOOLEAN DEFAULT false;

-- Add company size field to organizations table
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS company_size VARCHAR(50);

-- Update existing users to have onboarding completed (migration safety)
UPDATE users SET onboarding_completed = true WHERE onboarding_completed IS NULL;
