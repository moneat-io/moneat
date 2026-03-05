# MCP Tools Reference

Complete reference for all MCP tools and resources. Tools marked with ✏️ are write operations.

## Issue Tools

### `list_issues`
List issues for a project with optional filters.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |
| `status` | string | No | Filter: `unresolved`, `resolved`, `ignored` |
| `page` | number | No | Page number (default 1) |
| `limit` | number | No | Results per page (default 25) |

### `get_issue`
Get detailed information about a specific issue including stack trace.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `issue_id` | string | Yes | Issue ID |

### `get_issue_events`
Get events associated with a specific issue.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `issue_id` | string | Yes | Issue ID |
| `limit` | number | No | Max events (default 50) |

### ✏️ `update_issue_status`
Update issue status (resolve, ignore, or reopen).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `issue_id` | string | Yes | Issue ID |
| `status` | string | Yes | `resolved`, `ignored`, or `unresolved` |

## Project Tools

### `list_projects`
List all projects in the organization.

No parameters.

### ✏️ `create_project`
Create a new project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Project name |
| `platform` | string | Yes | Platform (e.g., `kotlin`, `javascript`, `python`) |

### `get_project`
Get project details including DSN and keys.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |

### `get_project_stats`
Get error counts and event volume statistics.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |
| `period` | string | No | `1h`, `6h`, `24h`, `7d`, `30d` (default `7d`) |

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
| `host_id` | integer | No | Host ID |
| `container_name` | string | No | Container name |

### `aggregate_logs`
Aggregate log volume and error rate over time buckets.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `from` | string | No | Start time (ISO 8601) |
| `to` | string | No | End time (ISO 8601) |
| `interval` | string | No | `1m`, `5m`, `15m`, `1h`, `1d` |
| `query` | string | No | Search query |
| `levels` | string | No | Comma-separated log levels |
| `service` | string | No | Service filter |
| `group_by` | string | No | Group by field (e.g., `level`, `service_name`) |

### `get_log_top_values`
Get top values for a log field.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `field` | string | Yes | `message`, `service_name`, `level`, or `host` |
| `limit` | number | No | Max results (default 10) |
| `from` | string | No | Start time (ISO 8601) |
| `to` | string | No | End time (ISO 8601) |
| `query` | string | No | Search query |

### `get_log_filters`
Get available log facets with counts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `from` | string | No | Start time (ISO 8601) |
| `to` | string | No | End time (ISO 8601) |

## Monitor Tools

### `list_hosts`
List all monitored hosts with status. No parameters.

### `get_host_status`
Get status for a specific host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |

### ✏️ `create_host`
Register a new monitored host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Host name |

### ✏️ `delete_host`
Delete a monitored host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |

### `get_host_metrics`
Get historical metrics for a host (CPU, memory, disk, network).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |
| `hours` | number | No | Hours of history (default 24, max 168) |

### `get_container_metrics`
Get metrics for containers on a host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |
| `container_name` | string | No | Filter by container name |

### `get_host_logs`
Get host-level logs for a host. Delegates to `query_logs` with a host filter, filtering by `host_id`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |
| `hours` | number | No | Hours of history (default 24) |
| `level` | string | No | `error`, `warn`, `info`, `debug` |

### `get_alert_config`
Get current alert thresholds for a host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |

## APM Tools

### `list_transactions`
List transactions with performance stats.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |
| `period` | string | No | `1h`, `6h`, `24h`, `7d`, `30d` |
| `environment` | string | No | Environment |
| `operation` | string | No | Operation type |

### `get_trace`
Get a full transaction/trace by event ID.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `event_id` | string | Yes | Transaction event ID |

### `get_transaction_stats`
Get P50/P95/P99 latency trends for transactions.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |
| `period` | string | No | `1h`, `6h`, `24h`, `7d`, `30d` (default `24h`) |
| `environment` | string | No | Environment |
| `operation` | string | No | Operation type |

### `get_related_errors`
Get errors correlated to a transaction trace.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `event_id` | string | Yes | Transaction event ID |
| `limit` | number | No | Max results (default 20) |

### `get_span_details`
Get span details for a transaction.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `event_id` | string | Yes | Transaction event ID |

### `get_issue_transactions`
Get APM traces related to an error issue.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `issue_id` | string | Yes | Issue ID |
| `limit` | number | No | Max results (default 50) |

### `list_feedback`
List user feedback for a project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |
| `limit` | number | No | Max results (default 50) |

## Dashboard Tools

### `list_dashboards`
List all dashboards. Optional `project_id` filter.

### `get_dashboard`
Get a dashboard with its widgets.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |

### ✏️ `create_dashboard`
Create a new dashboard.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Dashboard name |
| `description` | string | No | Description |

### ✏️ `update_dashboard`
Update a dashboard (title, description, widgets).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `title` | string | No | New title |
| `description` | string | No | New description |

### ✏️ `delete_dashboard`
Delete a dashboard.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |

### ✏️ `create_dashboard_alert`
Create an alert on a dashboard.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `name` | string | Yes | Alert name |
| `condition` | string | Yes | `gt`, `lt`, `eq` |
| `threshold` | number | Yes | Threshold value |
| `severity` | string | No | `warning`, `critical` (default `warning`) |

### ✏️ `update_dashboard_alert`
Update a dashboard alert.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `alert_id` | number | Yes | Alert ID |
| `name` | string | No | Alert name |
| `condition` | string | No | `gt`, `lt`, `eq` |
| `threshold` | number | No | Threshold value |
| `severity` | string | No | `warning`, `critical` |

### ✏️ `delete_dashboard_alert`
Delete a dashboard alert.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `alert_id` | number | Yes | Alert ID |

### `get_dashboard_templates`
List available dashboard templates. No parameters.

### ✏️ `import_dashboard`
Import a dashboard from Datadog or Grafana JSON.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `format` | string | Yes | `datadog` or `grafana` |
| `json` | string | Yes | Dashboard JSON from source platform |

## Alert Tools

### `list_alerts`
List monitoring alerts for a host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |

### ✏️ `create_alert`
Create a monitoring alert on a host.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |
| `metric` | string | Yes | `cpu`, `memory`, `disk`, `network_in`, `network_out` |
| `condition` | string | Yes | `gt`, `lt`, `eq` |
| `threshold` | number | Yes | Threshold value |
| `duration_seconds` | number | No | Duration before triggering (default 300) |
| `incident_severity` | string | No | `P1`–`P5` (default `P3`) |

### ✏️ `update_alert`
Update a monitoring alert.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |
| `alert_id` | string | Yes | Alert UUID |
| `metric` | string | No | Metric name |
| `condition` | string | No | `gt`, `lt`, `eq` |
| `threshold` | number | No | Threshold value |
| `duration_seconds` | number | No | Duration in seconds |

### ✏️ `delete_alert`
Delete a monitoring alert.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host_id` | integer | Yes | Host ID |
| `alert_id` | string | Yes | Alert UUID |

### `list_silence_periods`
List alert silence periods. No parameters.

### ✏️ `create_silence_period`
Create an alert silence period.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `starts_at` | integer | Yes | Silence start time (epoch milliseconds) |
| `ends_at` | integer | Yes | Silence end time (epoch milliseconds) |
| `reason` | string | No | Reason for silencing |

### ✏️ `delete_silence_period`
Delete an alert silence period.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `silence_period_id` | number | Yes | Silence period ID |

## Infrastructure Tools

### `list_containers`
List containers across hosts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host` | string | No | Filter by host |
| `limit` | number | No | Max results (default 100) |

### `list_processes`
List processes on hosts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `host` | string | No | Filter by host |
| `limit` | number | No | Max results (default 100) |

### `get_k8s_resources`
Get Kubernetes resources.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `resource_type` | string | No | K8s resource type |
| `limit` | number | No | Max results (default 100) |

### `get_network_connections`
Get network connections.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `limit` | number | No | Max results (default 100) |

### `get_dbm_queries`
Get database monitoring slow queries.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `limit` | number | No | Max results (default 100) |

## Uptime Tools

### `list_uptime_monitors`
List all uptime monitors. No parameters.

### `get_monitor_heartbeats`
Get heartbeats for an uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `monitor_id` | string | Yes | Monitor UUID |
| `hours` | number | No | Hours of history (default 24) |

### ✏️ `create_uptime_monitor`
Create a new uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Monitor name |
| `url` | string | Yes | URL to monitor |
| `type` | string | Yes | `http`, `tcp`, `ping`, `push` |
| `interval_seconds` | number | No | Check interval (default 60) |

### ✏️ `update_uptime_monitor`
Update an uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `monitor_id` | string | Yes | Monitor UUID |
| `name` | string | No | Monitor name |
| `url` | string | No | URL to monitor |
| `interval_seconds` | number | No | Check interval |

### ✏️ `delete_uptime_monitor`
Delete an uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `monitor_id` | string | Yes | Monitor UUID |

### ✏️ `pause_uptime_monitor`
Pause an uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `monitor_id` | string | Yes | Monitor UUID |

### ✏️ `resume_uptime_monitor`
Resume a paused uptime monitor.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `monitor_id` | string | Yes | Monitor UUID |

## Status Page Tools

### `list_status_pages`
List all status pages. No parameters.

### ✏️ `create_status_page`
Create a new status page.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Page name |
| `slug` | string | Yes | URL slug |
| `description` | string | No | Page description |
| `is_public` | boolean | No | Public visibility (default true) |

### `get_status_page`
Get status page details.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `page_id` | string | Yes | Status page UUID |

### ✏️ `update_status_page`
Update a status page.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `page_id` | string | Yes | Status page UUID |
| `name` | string | No | Page name |
| `description` | string | No | Description |
| `is_public` | boolean | No | Public visibility |

### ✏️ `add_status_page_monitor`
Add an uptime monitor to a status page.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `page_id` | string | Yes | Status page UUID |
| `monitor_id` | string | Yes | Monitor UUID |
| `display_name` | string | No | Display name on page |
| `sort_order` | number | No | Sort order |

### ✏️ `create_status_page_incident`
Create an incident on a status page.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `page_id` | string | Yes | Status page UUID |
| `title` | string | Yes | Incident title |
| `status` | string | Yes | `investigating`, `identified`, `monitoring`, `resolved` |
| `impact` | string | No | `none`, `minor`, `major`, `critical` (default `none`) |
| `message` | string | Yes | Incident message |

### ✏️ `update_status_page_incident`
Update a status page incident.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `page_id` | string | Yes | Status page UUID |
| `incident_id` | string | Yes | Incident UUID |
| `status` | string | No | `investigating`, `identified`, `monitoring`, `resolved` |
| `impact` | string | No | `none`, `minor`, `major`, `critical` |
| `title` | string | No | Updated title |

### ✏️ `post_incident_update`
Post an update to a status page incident.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `page_id` | string | Yes | Status page UUID |
| `incident_id` | string | Yes | Incident UUID |
| `status` | string | Yes | `investigating`, `identified`, `monitoring`, `resolved` |
| `message` | string | Yes | Update message |

## Data Source Tools

### `list_datasources`
List custom data sources. No parameters.

### ✏️ `create_datasource`
Create a custom data source.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Data source name |
| `source_type` | string | Yes | `postgresql`, `mysql`, `clickhouse`, `prometheus`, `elasticsearch` |
| `host` | string | Yes | Host address |
| `port` | number | No | Port number |
| `database_name` | string | No | Database name |
| `username` | string | No | Username |
| `password` | string | No | Password |
| `description` | string | No | Description |

### `get_datasource_schema`
Get data source connection details.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `datasource_id` | number | Yes | Data source ID |

## Notification Tools

### `get_alert_notification_channels`
Get alert notification channel preferences. No parameters.

### ✏️ `update_alert_notification_channels`
Update alert notification channel preferences.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `alert_source` | string | Yes | `host_alert`, `uptime_alert`, `dashboard_alert` |
| `email_enabled` | boolean | Yes | Enable email notifications |
| `slack_enabled` | boolean | Yes | Enable Slack notifications |
| `discord_enabled` | boolean | Yes | Enable Discord notifications |

## On-Call Tools

### `list_incidents`
List on-call incidents.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | string | No | `triggered`, `acknowledged`, `resolved` |
| `priority` | string | No | `P1`–`P5` |
| `limit` | number | No | Max results (default 50) |

### `get_incident`
Get incident details.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `incident_id` | number | Yes | Incident ID |

### `list_schedules`
List on-call schedules. No parameters.

## Profile Tools

### `list_profiles`
List profiling data.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `service` | string | No | Service filter |
| `environment` | string | No | Environment filter |
| `limit` | number | No | Max results (default 50) |

## Release Tools

### `list_releases`
List releases for a project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |

### `get_release_stats`
Get releases with error/performance stats.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_id` | number | Yes | Project ID |

## Search Tools

### `global_search`
Search across issues, logs, and hosts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search query |
| `limit` | number | No | Max per category (default 10) |

## Summary Tools

### `get_infrastructure_summary`
Get aggregated infrastructure health across all hosts and uptime monitors.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `period` | string | No | `24h`, `7d`, `30d` (default `24h`) |

Returns host counts by status, uptime percentages, top alerts, and error-rate hosts.

### `get_overnight_summary`
Get summary of events during overnight window (10pm–8am).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `timezone` | string | No | IANA timezone (default `America/New_York`) |

Returns new/regressed issues, error spikes, host status changes, and log error volume.

### `get_weekly_report`
Get 7-day infrastructure health digest.

No parameters.

Returns error trends, P95 latency, uptime percentages, incident count/MTTR, noisiest issues, and resource utilization.

### `get_incident_context`
Get correlated context for an active incident.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `incident_id` | number | Yes | Incident ID |

Returns triggering alert, host metrics around trigger time, related logs, error spikes, recent deployments, and affected uptime monitors.

## MCP Resources

Resources are read-only data sources available via `resources/list` and `resources/read`:

| URI | Description |
|-----|-------------|
| `moneat://org/overview` | Organization summary (project count, hosts, alerts) |
| `moneat://projects` | All projects in the organization |
| `moneat://hosts/status` | All hosts with current status |
| `moneat://alerts/active` | Active alert silence periods |
| `moneat://incidents/active` | Currently active on-call incidents |
| `moneat://uptime/summary` | All uptime monitors with 24h/7d/30d percentages |
| `moneat://status-pages` | Status pages and current status |
| `moneat://infrastructure/health` | Quick health: host statuses, alert counts, uptime |
