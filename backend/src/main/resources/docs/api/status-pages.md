# Status Pages API

## Endpoints

### GET /v1/status-pages
List all status pages for the organization.

### POST /v1/status-pages
Create a new status page.

**Parameters:**
| name | type | required | description |
|------|------|----------|-------------|
| name | string | yes | Status page name |
| slug | string | yes | URL slug (unique) |
| description | string | no | Page description |

### GET /v1/status-pages/{pageId}
Get status page details.

### PUT /v1/status-pages/{pageId}
Update a status page.

### DELETE /v1/status-pages/{pageId}
Delete a status page. **Destructive - requires confirmation.**

### POST /v1/status-pages/{pageId}/monitors
Add or reorder monitors on the status page.

### DELETE /v1/status-pages/{pageId}/monitors/{monitorId}
Remove a monitor from the status page.

### GET /v1/status-pages/{pageId}/incidents
List incidents for a status page.

### POST /v1/status-pages/{pageId}/incidents
Create a new incident on the status page.

**Parameters:**
| name | type | required | description |
|------|------|----------|-------------|
| title | string | yes | Incident title |
| status | string | yes | investigating, identified, monitoring, resolved |
| impact | string | yes | none, minor, major, critical |
| message | string | yes | Initial update message |
| affectedMonitorIds | array | no | Monitor IDs affected |

### PUT /v1/status-pages/{pageId}/incidents/{incidentId}
Update an incident.

### POST /v1/status-pages/{pageId}/incidents/{incidentId}/updates
Post a new update to an incident.
