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

## Feature Flag Tools

### `list_feature_flag_environments`
List feature flag environments.

No parameters.

### ✏️ `create_feature_flag_environment`
Create a feature flag environment.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `key` | string | Yes | Stable environment key, such as `production` or `qa` |
| `name` | string | Yes | Human-readable environment name |
| `description` | string | No | Environment description |

### `list_feature_flags`
List feature flags and environments for the organization.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `environment` | string | No | Environment key to prioritize in config results |

### `get_feature_flag`
Get one feature flag by key.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `flag_key` | string | Yes | Feature flag key |
| `environment` | string | No | Environment key to return only that config |

### ✏️ `create_feature_flag`
Create a feature flag with variants for OpenFeature evaluation.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `key` | string | Yes | Stable flag key, such as `checkout.enabled` |
| `name` | string | Yes | Human-readable flag name |
| `value_type` | string | Yes | `BOOLEAN`, `STRING`, `INTEGER`, `DOUBLE`, or `OBJECT` |
| `variants` | array | Yes | Variant objects with `key`, optional `name`, and JSON `value` |
| `description` | string | No | Internal description |
| `client_visible` | boolean | No | Allow client SDK keys to evaluate this flag |
| `tags` | array | No | Grouping tags |
| `default_variant_key` | string | No | Variant returned when no targeting rule matches |
| `off_variant_key` | string | No | Variant returned while the flag is disabled |

### ✏️ `update_feature_flag`
Update a feature flag's metadata, client visibility, tags, or variants.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `flag_key` | string | Yes | Feature flag key |
| `name` | string | No | Updated display name |
| `description` | string | No | Updated internal description |
| `client_visible` | boolean | No | Allow client SDK keys to evaluate this flag |
| `tags` | array | No | Replacement grouping tags |
| `variants` | array | No | Replacement variant objects with `key`, optional `name`, and JSON `value` |

### ✏️ `delete_feature_flag`
Archive a feature flag by key.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `flag_key` | string | Yes | Feature flag key |

### ✏️ `update_feature_flag_config`
Update one environment-specific feature flag config.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `flag_key` | string | Yes | Feature flag key |
| `environment` | string | Yes | Environment key |
| `enabled` | boolean | No | Enable or disable this flag in the environment |
| `default_variant_key` | string | No | Variant returned when no targeting rule matches |
| `off_variant_key` | string | No | Variant returned while the flag is disabled |
| `rules` | object | No | Rules JSON object, such as `{"rules": []}` |

### `list_feature_flag_segments`
List reusable feature flag targeting segments.

No parameters.

### ✏️ `upsert_feature_flag_segment`
Create or update a reusable feature flag targeting segment.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `key` | string | Yes | Stable segment key |
| `name` | string | Yes | Human-readable segment name |
| `description` | string | No | Segment description |
| `conditions` | object | No | Targeting conditions JSON object, such as `{"all": []}` |

### ✏️ `delete_feature_flag_segment`
Archive a feature flag segment by key.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `segment_key` | string | Yes | Segment key |

### `list_feature_flag_sdk_keys`
List active feature flag SDK keys.

No parameters.

### ✏️ `create_feature_flag_sdk_key`
Create a feature flag SDK key and return its one-time secret.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `environment_key` | string | Yes | Environment key for this SDK key |
| `name` | string | Yes | Human-readable SDK key name |
| `key_type` | string | Yes | `server` or `client` |

### ✏️ `revoke_feature_flag_sdk_key`
Revoke an active feature flag SDK key.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sdk_key_id` | integer | Yes | Feature flag SDK key ID |

### `list_feature_flag_audit_events`
List recent feature flag audit events.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `limit` | integer | No | Maximum events to return, from 1 to 100 |

### `get_feature_flag_analytics`
Get feature flag evaluation and tracking analytics.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `environment` | string | No | Environment key |
| `hours` | integer | No | Lookback window in hours |

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
| `interval` | string | No | `1m`, `5m`, `15m`, `1h`, `2h`, `4h`, `12h`, `1d` |
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
Update a dashboard's title and description.

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

### ✏️ `create_dashboard_widget`
Create a widget on a dashboard and append it to the bottom of the grid when `grid_y` is omitted.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `widget_type` | string | Yes | Widget type |
| `title` | string | No | Widget title |
| `grid_x`, `grid_y`, `grid_w`, `grid_h` | integer | No | Grid position and size |
| `query_configs` | array | No | QueryDsl array |
| `display_config` | object | No | Display config |
| `sort_order` | integer | No | Sort order |

### ✏️ `update_dashboard_widget`
Update one widget while preserving the rest of the dashboard.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `widget_id` | number | Yes | Widget ID |
| `title`, `widget_type` | string | No | Widget title or type |
| `grid_x`, `grid_y`, `grid_w`, `grid_h` | integer | No | Grid position and size |
| `query_configs` | array | No | QueryDsl array |
| `display_config` | object | No | Display config |
| `sort_order` | integer | No | Sort order |

### ✏️ `delete_dashboard_widget`
Delete one widget from a dashboard.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `widget_id` | number | Yes | Widget ID |

### `preview_dashboard_widget_query`
Preview a widget query with dashboard variable substitution and datasource resolution.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `query_config` | object | Yes | QueryDsl config |
| `project_id` | string or number | No | Project resource ID or legacy numeric project ID when unscoped |
| `variables` | object | No | Variable values |
| `time_range` | object | No | Time range override |

### ✏️ `replace_dashboard_widgets`
Replace every widget on a dashboard after verifying the current widget count.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `widgets` | array | Yes | Replacement widget objects |
| `expected_widget_count` | integer | Yes | Current widget count expected by caller |

### ✏️ `create_dashboard_alert`
Create an alert on a dashboard widget.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `widget_id` | number | Yes | Widget ID |
| `name` | string | Yes | Alert name |
| `condition` | string | Yes | `gt`, `lt`, `eq`, `gte`, `lte`, or persisted operators `>`, `<`, `==`, `>=`, `<=` |
| `threshold` | number | Yes | Threshold value |
| `duration_seconds` | integer | No | Duration before firing (default 0) |
| `incident_severity` | string | No | `P0`-`P5` or `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`; `P0` maps to `CRITICAL` |

### ✏️ `update_dashboard_alert`
Update a dashboard alert.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dashboard_id` | number | Yes | Dashboard ID |
| `alert_id` | number | Yes | Alert ID |
| `name` | string | No | Alert name |
| `condition` | string | No | `gt`, `lt`, `eq`, `gte`, `lte`, or persisted operators `>`, `<`, `==`, `>=`, `<=` |
| `threshold` | number | No | Threshold value |
| `duration_seconds` | integer | No | Duration before firing |
| `incident_severity` | string | No | `P0`-`P5` or `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`; `P0` maps to `CRITICAL` |
| `enabled` | boolean | No | Enable or disable the alert |

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
| `incident_severity` | string | No | `P0`-`P5` (default `P3`) |

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

## Workflow Tools

### `list_workflows`
List workflows in the organization. No parameters.

### `get_workflow`
Get a workflow, including the latest graph.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |

### ✏️ `create_workflow`
Create a workflow draft.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Workflow name |
| `trigger_name` | string | Yes | Trigger from `get_workflow_catalog` |
| `enabled` | boolean | No | Whether the workflow is enabled |
| `graph` / `graph_json` | object/string | No | Graph definition with nodes and edges |
| `conditions` / `conditions_json` | array/string | No | Legacy condition array |
| `steps` / `steps_json` | array/string | No | Legacy step array |
| `once_for_template` / `once_for_template_json` | array/string | No | Deduplication references |

### ✏️ `update_workflow`
Update workflow metadata or create a new draft version.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |
| `name` | string | No | Updated workflow name |
| `enabled` | boolean | No | Whether the workflow is enabled |
| `graph` / `graph_json` | object/string | No | Replacement graph definition |
| `conditions` / `conditions_json` | array/string | No | Replacement legacy condition array |
| `steps` / `steps_json` | array/string | No | Replacement legacy step array |
| `once_for_template` / `once_for_template_json` | array/string | No | Replacement deduplication references |

### ✏️ `delete_workflow`
Delete a workflow.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |

### ✏️ `publish_workflow`
Publish the latest workflow version so it can run for matching triggers.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |

### ✏️ `unpublish_workflow`
Unpublish the latest workflow version.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |

### ✏️ `run_workflow`
Start a manual workflow run.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |
| `scope` / `scope_json` | object/string | No | Additional workflow scope |

### ✏️ `create_workflow_instance`
Create an API-triggered workflow instance.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | API-triggered workflow ID |
| `scope` / `scope_json` | object/string | No | Instance scope |

### ✏️ `cancel_workflow_run`
Cancel a workflow run.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |
| `run_id` | integer | Yes | Run ID |

### `list_workflow_runs`
List recent workflow runs.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |
| `limit` | integer | No | Max runs (default 50, max 100) |

### `get_workflow_run`
Get a workflow run with step progress.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Workflow ID |
| `run_id` | integer | Yes | Run ID |

### `get_workflow_catalog`
Get workflow triggers, resources, actions, and graph node types. No parameters.

### `list_workflow_blueprints`
List curated workflow blueprints. No parameters.

### `get_workflow_blueprint`
Get one workflow blueprint with its graph.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `key` | string | Yes | Blueprint key |

### `list_workflow_audit`
List workflow audit events.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | No | Filter to one workflow |
| `limit` | integer | No | Max events (default 100, max 500) |

### `get_workflow_webhook_signing`
Get the signed webhook ingress URL and signing secret for a webhook-triggered workflow.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `workflow_id` | integer | Yes | Webhook-triggered workflow ID |

## Security Tools

### `list_security_signals`
List security signals with optional filters.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | string | No | `open`, `under_review`, or `archived` |
| `severity` | string | No | `info`, `low`, `medium`, `high`, or `critical` |
| `source` | string | No | Signal source, such as `detection` or `vulnerability` |
| `from` / `to` | string | No | ISO-8601 time bounds |
| `limit` / `offset` | integer | No | Pagination controls |

### `get_security_signal`
Get a security signal with evidence, audit trail, sample events, and threat intel.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `security_signal_id` | integer | Yes | Security signal ID |

### ✏️ `triage_security_signal`
Change signal status, assignment, or add a note.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `security_signal_id` | integer | Yes | Security signal ID |
| `status` | string | No | `open`, `under_review`, or `archived` |
| `reason` | string | No | Archive reason: `true_positive`, `false_positive`, or `benign` |
| `assignee_user_id` | integer | No | User ID to assign |
| `clear_assignee` | boolean | No | Clear the current assignee |
| `note` | string | No | Triage note |

### Detection Rule Tools
Use these to manage compiler-validated security detection rules.

### `list_detection_rules`
List detection rules. No parameters.

### `get_detection_rule`
Get one rule.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `detection_rule_id` | integer | Yes | - | Detection rule ID |

### ✏️ `create_detection_rule`
Create a compiler-validated detection rule.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `name` | string | Yes | - | Detection rule name |
| `description` | string | No | `""` | Detection rule description |
| `source` | string | No | `logs` | Telemetry source |
| `filter` | string | No | `""` | Log query filter expression |
| `group_by` | string[] | No | `[]` | Group-by columns, such as `host`, `service`, or `tags['user']` |
| `window_seconds` | integer | No | `300` | Evaluation window in seconds |
| `type` | string | No | `threshold` | `threshold`, `new_value`, or `rate_anomaly` |
| `threshold_count` | integer | No | `null` | Threshold count for threshold or rate rules |
| `severity` | string | No | `medium` | `info`, `low`, `medium`, `high`, or `critical` |
| `signal_title` | string | No | `""` | Signal title template |
| `signal_message` | string | No | `""` | Signal message template |
| `suppressions` | string[] | No | `[]` | Suppression keys |
| `enabled` | boolean | No | `false` | Enable the rule after creation |
| `tags` | string[] | No | `[]` | Rule tags, such as `mitre:T1059` |

### ✏️ `update_detection_rule`
Update a compiler-validated detection rule.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `detection_rule_id` | integer | Yes | - | Detection rule ID |
| `name` | string | No | Current value | Detection rule name |
| `description` | string | No | Current value | Detection rule description |
| `source` | string | No | Current value | Telemetry source |
| `filter` | string | No | Current value | Log query filter expression |
| `group_by` | string[] | No | Current value | Group-by columns, such as `host`, `service`, or `tags['user']` |
| `window_seconds` | integer | No | Current value | Evaluation window in seconds |
| `type` | string | No | Current value | `threshold`, `new_value`, or `rate_anomaly` |
| `threshold_count` | integer | No | Current value | Threshold count for threshold or rate rules |
| `severity` | string | No | Current value | `info`, `low`, `medium`, `high`, or `critical` |
| `signal_title` | string | No | Current value | Signal title template |
| `signal_message` | string | No | Current value | Signal message template |
| `suppressions` | string[] | No | Current value | Suppression keys |
| `enabled` | boolean | No | Current value | Enable or disable the rule |
| `tags` | string[] | No | Current value | Rule tags, such as `mitre:T1059` |

### ✏️ `delete_detection_rule`
Delete a detection rule.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `detection_rule_id` | integer | Yes | - | Detection rule ID |

### `preview_detection_rule`
Preview rule matches without writing signals.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `detection_rule_id` | integer | Yes | - | Detection rule ID |

### `get_detection_coverage`
Get MITRE ATT&CK coverage for enabled rules. No parameters.

### `list_detection_templates`
List starter-pack templates. No parameters.

### ✏️ `install_detection_template`
Install a template as a disabled rule.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `template_id` | string | Yes | - | Detection template ID |

### Vulnerability Tools

### `get_vulnerability_summary`
Get package and finding counts. No parameters.

### `list_vulnerability_inventory`
List SBOM package inventory with finding counts.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `search` | string | No | - | Search package, version, target, host, image, or purl |
| `package` | string | No | - | Exact package name filter |
| `target` | string | No | - | Target name filter |
| `limit` | integer | No | `100` | Max results, capped at `500` |
| `offset` | integer | No | `0` | Result offset for pagination |

### `list_vulnerability_findings`
List vulnerability findings derived from SBOM inventory.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `search` | string | No | - | Search advisory, CVE, package, target, or fixed version |
| `package` | string | No | - | Exact package name filter |
| `severity` | string | No | - | `info`, `low`, `medium`, `high`, or `critical` |
| `status` | string | No | - | `open`, `under_review`, or `archived` |
| `limit` | integer | No | `100` | Max results, capped at `500` |
| `offset` | integer | No | `0` | Result offset for pagination |

### `export_vulnerability_sbom`
Export inventory as CycloneDX or SPDX JSON.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `format` | string | Yes | - | `cyclonedx` or `spdx` |

### Security Event And Compliance Tools

### `list_security_events`
List runtime security events.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `severity` | string | No | - | Security event severity |
| `host` | string | No | - | Literal host substring filter |
| `rule_id` | string | No | - | Runtime security rule ID |
| `limit` | integer | No | `50` | Max results, capped at `200` |
| `offset` | integer | No | `0` | Result offset for pagination |

### `get_security_event`
Get one runtime security event.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `security_event_id` | string | Yes | - | Runtime security event ID |

### `get_compliance_summary`
Get finding counts by framework and status. No parameters.

### `get_compliance_trends`
Get 14-day compliance trends by framework. No parameters.

### `list_compliance_findings`
List compliance findings.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `framework` | string | No | - | Compliance framework filter |
| `status` | string | No | - | `passed`, `failed`, `skipped`, or `error` |
| `limit` | integer | No | `50` | Max results, capped at `200` |
| `offset` | integer | No | `0` | Result offset for pagination |

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
| `priority` | string | No | `P0`-`P5` |
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
| `moneat://workflows/overview` | Workflow counts, recent runs, success rate, and top workflows |
| `moneat://workflows/usage` | Workflow execution usage for the current billing period |
| `moneat://workflows/catalog` | Workflow triggers, actions, graph node types, and blueprints |
| `moneat://security/summary` | Open signals, detection coverage, vulnerability counts, and compliance summary |
| `moneat://security/signals/open` | Open security signals |
| `moneat://security/detection/coverage` | MITRE ATT&CK coverage for enabled detection rules |
