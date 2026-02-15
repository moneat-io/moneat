# Issues API

## Endpoints

### GET /v1/projects/{projectId}/issues
List issues for a project.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| range | string | 24h | Time range (1h, 24h, 7d, 30d) |
| status | string | - | Filter by status (unresolved, resolved, ignored) |
| level | string | - | Filter by level (error, warning, info) |
| sort | string | lastSeen | Sort field (lastSeen, firstSeen, eventCount) |
| query | string | - | Search in title and culprit |
| cursor | string | - | Pagination cursor |
| limit | integer | 25 | Results per page |

### GET /v1/issues/{issueId}
Get issue details including latest event data.

### GET /v1/issues/{issueId}/events
Get events for a specific issue.

### PATCH /v1/issues/{issueId}
Update issue status.

**Parameters:**
| name | type | description |
|------|------|-------------|
| status | string | New status: resolved, ignored, unresolved |

### GET /v1/issues/{issueId}/replays
Get session replays associated with an issue.
