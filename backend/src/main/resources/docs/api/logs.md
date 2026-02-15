# Logs API

## Endpoints

### GET /v1/projects/{projectId}/logs
Query logs for a project.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| q | string | - | Full-text search query |
| level | string | - | Filter by level (info, warn, error, debug, fatal) |
| service | string | - | Filter by service name |
| environment | string | - | Filter by environment |
| from | string | -1h | Start time (ISO 8601 or relative) |
| to | string | now | End time |
| limit | integer | 100 | Results per page (max 1000) |
| cursor | string | - | Pagination cursor |
| tag | string | - | Filter by tag (format: key:value) |

### GET /v1/projects/{projectId}/logs/filters
Get available filter options (levels, services, environments).

### GET /v1/projects/{projectId}/logs/aggregate
Aggregate log counts over time for histogram.

### GET /v1/projects/{projectId}/logs/top
Get top values for a specific field.

**Query parameters:**
| name | type | description |
|------|------|-------------|
| field | string | Field to aggregate (service, level, etc.) |

### GET /v1/projects/{projectId}/logs/export
Export logs as CSV file.
