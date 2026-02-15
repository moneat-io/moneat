# Projects API

## Endpoints

### GET /v1/projects
List all projects in the organization.

### POST /v1/projects
Create a new project.

**Parameters:**
| name | type | required | description |
|------|------|----------|-------------|
| name | string | yes | Project name |
| framework | string | no | Framework identifier (react, node, python, etc.) |

### GET /v1/projects/{projectId}
Get project details including DSN keys.

### PUT /v1/projects/{projectId}
Update project name or settings.

### DELETE /v1/projects/{projectId}
Delete a project and all its data. **Destructive - requires confirmation.**

### GET /v1/projects/{projectId}/stats
Get project statistics.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| range | string | 24h | Time range (1h, 24h, 7d, 30d) |

Returns: totalEvents, totalIssues, unresolvedIssues, affectedUsers, event timelines, and breakdowns by level/platform/browser/environment.

### GET /v1/projects/{projectId}/releases
List releases for a project.

### GET /v1/projects/{projectId}/releases/{version}/stats
Get statistics for a specific release version.
