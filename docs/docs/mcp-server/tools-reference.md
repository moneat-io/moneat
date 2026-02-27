# MCP Tools Reference

## Issue Tools

### `list_issues`
List issues for a project with optional filters.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |
| `status` | string | No | Filter: `unresolved`, `resolved`, `ignored` |
| `page` | number | No | Page number (default 1) |
| `limit` | number | No | Results per page (default 25) |

**Required scopes:** `event:read`

### `get_issue`
Get detailed information about a specific issue including stack trace.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `issue_id` | string | Yes | Issue ID |

**Required scopes:** `event:read`

### `get_issue_events`
Get events associated with a specific issue.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `issue_id` | string | Yes | Issue ID |
| `limit` | number | No | Max events (default 50) |

**Required scopes:** `event:read`

### `update_issue_status`
Update issue status (resolve, ignore, or reopen).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `issue_id` | string | Yes | Issue ID |
| `status` | string | Yes | `resolved`, `ignored`, or `unresolved` |

**Required scopes:** `project:write`

## Log Tools

### `query_logs`
Search logs with filters.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | No | Search query |
| `levels` | array | No | Log levels (e.g., `["error", "warn"]`) |
| `service` | string | No | Service name |
| `environment` | string | No | Environment |
| `from` | string | No | Start time (ISO 8601) |
| `to` | string | No | End time (ISO 8601) |
| `limit` | number | No | Max results (default 100) |
| `cursor` | string | No | Pagination cursor |
| `system_id` | string | No | Host/system ID |
| `container_name` | string | No | Container name |

**Required scopes:** `event:read`

## Monitor Tools

### `list_hosts`
List all monitored hosts with status. No parameters.

**Required scopes:** `org:read`

### `get_host_status`
Get status for a specific host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `system_id` | string | Yes | Host/system UUID |

**Required scopes:** `org:read`

### `create_host`
Register a new monitored host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Host name |

**Required scopes:** `project:write`

## APM Tools

### `list_transactions`
List transactions with performance stats.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |
| `period` | string | No | `1h`, `6h`, `24h`, `7d`, `30d` |
| `environment` | string | No | Environment |
| `operation` | string | No | Operation type |

**Required scopes:** `event:read`

### `get_trace`
Get a full transaction/trace by event ID.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `event_id` | string | Yes | Transaction event ID |

**Required scopes:** `event:read`

## Dashboard Tools

### `list_dashboards`
List all dashboards. Optional `project_id` filter.

**Required scopes:** `project:read`

### `get_dashboard`
Get a dashboard with its widgets.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |

**Required scopes:** `project:read`

### `create_dashboard`
Create a new dashboard.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Dashboard name |
| `description` | string | No | Description |

**Required scopes:** `project:write`

## Alert Tools

### `list_alerts`
List monitoring alerts for a host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `system_id` | string | Yes | Host/system UUID |

**Required scopes:** `org:read`

### `list_silence_periods`
List alert silence periods. No parameters.

**Required scopes:** `org:read`

## Infrastructure Tools

### `list_containers`
List containers across hosts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host` | string | No | Filter by host |
| `limit` | number | No | Max results (default 100) |

**Required scopes:** `org:read`

### `list_processes`
List processes on hosts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host` | string | No | Filter by host |
| `limit` | number | No | Max results (default 100) |

**Required scopes:** `org:read`

### `get_k8s_resources`
Get Kubernetes resources.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `resource_type` | string | No | K8s resource type |
| `limit` | number | No | Max results (default 100) |

**Required scopes:** `org:read`

### `get_network_connections`
Get network connections.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `limit` | number | No | Max results (default 100) |

**Required scopes:** `org:read`

### `get_dbm_queries`
Get database monitoring slow queries.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `limit` | number | No | Max results (default 100) |

**Required scopes:** `org:read`

## Uptime Tools

### `list_uptime_monitors`
List all uptime monitors. No parameters.

**Required scopes:** `org:read`

### `get_monitor_heartbeats`
Get heartbeats for an uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `monitor_id` | string | Yes | Monitor UUID |
| `hours` | number | No | Hours of history (default 24) |

**Required scopes:** `org:read`

### `create_uptime_monitor`
Create a new uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Monitor name |
| `url` | string | Yes | URL to monitor |
| `type` | string | Yes | `http`, `tcp`, `ping`, `push` |
| `interval_seconds` | number | No | Check interval (default 60) |

**Required scopes:** `project:write`

## On-Call Tools

### `list_incidents`
List on-call incidents.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | string | No | `triggered`, `acknowledged`, `resolved` |
| `priority` | string | No | `P1`–`P5` |
| `limit` | number | No | Max results (default 50) |

**Required scopes:** `org:read`

### `get_incident`
Get incident details.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `incident_id` | number | Yes | Incident ID |

**Required scopes:** `org:read`

### `list_schedules`
List on-call schedules. No parameters.

**Required scopes:** `org:read`

## Profile Tools

### `list_profiles`
List profiling data.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `service` | string | No | Service filter |
| `environment` | string | No | Environment filter |
| `limit` | number | No | Max results (default 50) |

**Required scopes:** `event:read`

## Release Tools

### `list_releases`
List releases for a project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |

**Required scopes:** `releases:read`

### `get_release_stats`
Get releases with error/performance stats.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |

**Required scopes:** `releases:read`

## Search Tools

### `global_search`
Search across issues, logs, and hosts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search query |
| `limit` | number | No | Max per category (default 10) |

**Required scopes:** `event:read`, `org:read`

## MCP Resources

Resources are read-only data sources available via `resources/list` and `resources/read`:

| URI | Description | Required scopes |
|-----|-------------|-----------------|
| `moneat://org/overview` | Organization summary (project count, hosts, alerts) | `org:read` |
| `moneat://projects` | All projects in the organization | `project:read` |
| `moneat://hosts/status` | All hosts with current status | `org:read` |
| `moneat://alerts/active` | Active alert silence periods | `org:read` |
