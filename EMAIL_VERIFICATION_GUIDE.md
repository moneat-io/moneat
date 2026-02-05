# Email Verification - Testing Guide

## Overview

Email verification has been added to the signup process. Users will receive a verification email after signing up and can verify their email address by clicking the link in the email.

## What's Been Implemented

### Database Changes
- Added `email_verified` (boolean, default: false) to users table
- Added `email_verification_token` (varchar) to users table
- Added `email_verification_expires_at` (bigint timestamp) to users table
- Created index on `email_verification_token` for faster lookups
- Existing users automatically marked as verified during migration

### New API Endpoints

1. **POST /auth/verify-email** - Verify email with token
   ```json
   {
     "token": "verification-token-from-email"
   }
   ```
   Response 200:
   ```json
   {
     "message": "Email verified successfully"
   }
   ```
   Response 400:
   ```json
   {
     "error": "Invalid or expired token"
   }
   ```

2. **POST /auth/resend-verification** - Resend verification email
   ```json
   {
     "email": "user@example.com"
   }
   ```
   Response 200:
   ```json
   {
     "message": "Verification email sent"
   }
   ```
   Response 400:
   ```json
   {
     "error": "Email already verified"
   }
   ```

### Updated Endpoints

- **POST /auth/signup** - Now returns `emailVerified: false` in the user object
  - Automatically generates verification token
  - Sends verification email (if AWS SES is configured)
  - Token expires in 24 hours

- **POST /auth/login** - Now includes `emailVerified` status in the response

### Email Service
- Uses AWS SES for sending emails
- Fallback to console logging if SES not configured
- Beautiful HTML email template built with Maizzle
- Includes plain text version for email clients that don't support HTML

## Configuration

Add the following environment variables to use AWS SES:

```bash
# Email configuration
EMAIL_FROM=noreply@yourdomain.com
FRONTEND_URL=http://localhost:3000

# AWS SES credentials
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your-access-key-id
AWS_SECRET_ACCESS_KEY=your-secret-access-key
```

**Note:** If AWS credentials are not provided, the service will log emails to the console instead of sending them.

## Testing

### 1. Sign Up a New User

```bash
curl -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "name": "Test User"
  }'
```

Response:
```json
{
  "token": "jwt-token-here",
  "user": {
    "id": 1,
    "email": "test@example.com",
    "name": "Test User",
    "emailVerified": false
  }
}
```

### 2. Check Backend Logs for Verification Email

If AWS SES is not configured, the verification email will be logged to the console:
```
Verification email sent to test@example.com
```

Or you'll see the email preview with the verification link.

### 3. Extract Verification Token

The verification URL will look like:
```
http://localhost:3000/verify-email?token=abc123xyz...
```

Extract the token from the URL.

### 4. Verify Email

```bash
curl -X POST http://localhost:8080/auth/verify-email \
  -H "Content-Type: application/json" \
  -d '{
    "token": "abc123xyz..."
  }'
```

Response:
```json
{
  "message": "Email verified successfully"
}
```

### 5. Resend Verification Email

```bash
curl -X POST http://localhost:8080/auth/resend-verification \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com"
  }'
```

Response:
```json
{
  "message": "Verification email sent"
}
```

### 6. Verify Status in Database

```bash
docker exec -i moneat-postgres psql -U moneat -d moneat \
  -c "SELECT id, email, email_verified FROM users;"
```

## Behavior Notes

1. **Login Allowed Before Verification**: Users can login before verifying their email, but the `emailVerified` field in the response will be `false`. The frontend can use this to show a banner encouraging email verification.

2. **Token Expiration**: Verification tokens expire after 24 hours. Users will need to use the resend endpoint to get a new token.

3. **Already Verified**: If a user tries to resend verification for an already verified email, they'll receive an error.

4. **Email Sending Failures**: If email sending fails, the signup will still succeed, but a warning will be logged. This prevents signup failures due to email service issues.

## Frontend Integration

The frontend should:

1. **After Signup**: Show a message like "Check your email to verify your account"

2. **Verification Page**: Create a `/verify-email` route that:
   - Extracts the `token` from the URL query params
   - Calls `POST /auth/verify-email` with the token
   - Shows success/error message

3. **Unverified Users**: Display a banner for logged-in users with `emailVerified: false`:
   ```
   "Please verify your email address. [Resend verification email]"
   ```

4. **Resend Button**: Calls `POST /auth/resend-verification` with the user's email

## Email Template

The verification email is built using Maizzle and located at:
- Source: `emails/src/templates/verify-email.html`
- Built: `emails/build/templates/email/verify-email.html`

To rebuild email templates after changes:
```bash
cd emails
npm run build:production
```

## Security Considerations

- Tokens are cryptographically secure (32 random bytes, URL-safe base64)
- Tokens expire after 24 hours
- Tokens are single-use (cleared after verification)
- Database index on token for fast lookups
- Rate limiting should be added to resend endpoint in production

## Files Changed

- `backend/src/main/kotlin/com/moneat/models/DatabaseModels.kt` - Added verification fields
- `backend/src/main/kotlin/com/moneat/models/ApiModels.kt` - Added verification request models
- `backend/src/main/kotlin/com/moneat/services/AuthService.kt` - Added verification logic
- `backend/src/main/kotlin/com/moneat/services/EmailService.kt` - New email service
- `backend/src/main/kotlin/com/moneat/routes/AuthRoutes.kt` - Added verification endpoints
- `backend/src/main/resources/application.conf` - Added email configuration
- `backend/src/main/resources/db/init.sql` - Updated schema
- `backend/src/main/resources/db/migration_add_email_verification.sql` - Migration script
- `backend/build.gradle.kts` - Added AWS SES dependency
- `emails/src/templates/verify-email.html` - Email template
