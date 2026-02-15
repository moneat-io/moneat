# Uptime Monitors API

## Endpoints

### GET /v1/uptime/monitors
List all uptime monitors for the organization.

### POST /v1/uptime/monitors
Create a new uptime monitor.

**Parameters:**
| name | type | required | default | description |
|------|------|----------|---------|-------------|
| name | string | yes | - | Monitor display name |
| url | string | yes (http/keyword) | - | URL to monitor |
| type | string | yes | - | Monitor type: http, keyword, ping, port, push |
| interval | integer | no | 300 | Check interval in seconds (60, 300, 900) |
| timeout | integer | no | 30 | Request timeout in seconds |
| method | string | no | GET | HTTP method (http type only) |
| expectedKeyword | string | no | - | Keyword to find in response (keyword type) |
| port | integer | no | - | Port number (port type only) |
| alertContacts | array | no | [] | Alert contact IDs |

### GET /v1/uptime/monitors/{id}
Get monitor details including current status and recent heartbeats.

### PUT /v1/uptime/monitors/{id}
Update an existing monitor. Same parameters as create.

### DELETE /v1/uptime/monitors/{id}
Delete a monitor. **Destructive action - requires confirmation.**

### POST /v1/uptime/monitors/{id}/pause
Pause monitoring for a specific monitor.

### POST /v1/uptime/monitors/{id}/resume
Resume monitoring for a paused monitor.

### GET /v1/uptime/monitors/{id}/heartbeats
Get heartbeat history for a monitor.

**Query parameters:**
| name | type | description |
|------|------|-------------|
| from | string | Start time (ISO 8601) |
| to | string | End time (ISO 8601) |

### POST /v1/uptime/push/{token}
Record a heartbeat for a push monitor (no auth required).
