# On-Call Management API

## Endpoints

### GET /v1/on-call/schedules
List all on-call schedules for the organization.

### POST /v1/on-call/schedules
Create a new on-call schedule.

**Parameters:**
| name | type | required | description |
|------|------|----------|-------------|
| name | string | yes | Schedule name |
| timezone | string | yes | IANA timezone (e.g., America/New_York) |
| rotationType | string | yes | daily, weekly, custom |
| participants | array | yes | List of user IDs |
| startDate | string | yes | Rotation start date (ISO 8601) |
| handoffTime | string | no | Time of day for handoffs (HH:mm, default 09:00) |

### GET /v1/on-call/schedules/{id}
Get schedule details including current on-call user.

### PUT /v1/on-call/schedules/{id}
Update a schedule.

### DELETE /v1/on-call/schedules/{id}
Delete a schedule. **Destructive - requires confirmation.**

### GET /v1/on-call/schedules/{id}/current
Get the user currently on call for this schedule.

### POST /v1/on-call/schedules/{id}/overrides
Create a temporary override.

**Parameters:**
| name | type | required | description |
|------|------|----------|-------------|
| userId | integer | yes | User taking over |
| startTime | string | yes | Override start (ISO 8601) |
| endTime | string | yes | Override end (ISO 8601) |

### GET /v1/on-call/alerts
List on-call alerts. Alerts carry `priority` values `P0` through `P5`.

### POST /v1/on-call/alerts/{id}/acknowledge
Acknowledge an on-call alert.

### POST /v1/on-call/alerts/{id}/resolve
Resolve an on-call alert.

### POST /v1/on-call/alerts/{id}/declare-incident
Declare an incident from an alert. The request must include incident `severity` (`SEV-0` through `SEV-4`).

### GET /v1/on-call/incidents
List declared incidents. Incidents carry `severity` values `SEV-0` through `SEV-4`.

### POST /v1/on-call/incidents/{id}/resolve
Resolve a declared incident.
