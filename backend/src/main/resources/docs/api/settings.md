# Settings API

## Endpoints

### Notification Preferences

#### GET /v1/notification-preferences
Get the user's global notification preferences.

#### PUT /v1/notification-preferences
Update global notification preferences.

#### PUT /v1/notification-preferences/{projectId}
Update notification preferences for a specific project.

### Alert Notification Preferences

#### GET /v1/alert-notification-preferences
Get alert notification preferences.

#### PUT /v1/alert-notification-preferences/{alertSource}
Update alert preferences for a specific source (uptime, oncall, monitoring).

### Auth Tokens

#### GET /v1/auth-tokens
List API authentication tokens.

#### POST /v1/auth-tokens
Create a new auth token.

**Parameters:**
| name | type | required | description |
|------|------|----------|-------------|
| name | string | yes | Token display name |
| scopes | array | yes | Permission scopes |
| expiresAt | string | no | Expiration date (ISO 8601) |

#### DELETE /v1/auth-tokens/{id}
Revoke an auth token. **Destructive - requires confirmation.**

### User Profile

#### GET /v1/user
Get current user profile and organization info.
