# Workflows API

## Overview

Workflows automate notification steps when alert lifecycle events occur. A workflow has one trigger, zero or more conditions, one or more steps, and a `once_for_template` that controls idempotency.

Every organization includes enabled alert and recovery notification workflows that can be edited or disabled.

## Endpoints

### GET /v1/workflows/catalog

Return the workflow catalog used by the builder.

The catalog includes:

- `triggers` - supported trigger definitions and their scope references
- `resources` - resource types and operations available to conditions
- `steps` - supported step definitions and their parameters

### GET /v1/workflows

List workflows for the current organization.

### POST /v1/workflows

Create a workflow.

**Body:**

| name | type | required | description |
|------|------|----------|-------------|
| name | string | yes | Workflow display name |
| trigger_name | string | yes | Trigger name from the catalog |
| enabled | boolean | no | Whether the workflow is active |
| conditions | array | no | Condition configuration |
| steps | array | no | Step configuration |
| once_for_template | array | no | Scope references used to build the idempotency key |

### POST /v1/workflows/preview

Render workflow notification messages without sending them.

**Body:**

| name | type | required | description |
|------|------|----------|-------------|
| trigger_name | string | yes | Trigger name from the catalog |
| steps | array | yes | Step configuration to preview |
| scope | object | no | Scope values that override the representative sample alert |

The response includes the sample scope and one preview item per notification step. Preview items include
channel, title or subject, text body, optional HTML body, fields, status color, CTA label, and CTA URL.

### POST /v1/workflows/test-message

Send representative workflow notification messages to the configured integrations without creating a workflow run.

**Body:**

| name | type | required | description |
|------|------|----------|-------------|
| trigger_name | string | yes | Trigger name from the catalog |
| steps | array | yes | Step configuration to send |
| scope | object | no | Scope values that override the representative sample alert |

The response includes the sample scope and one send result per notification step. Result statuses are
`sent`, `failed`, or `skipped`.

### GET /v1/workflows/{workflowId}

Get a workflow by ID.

### PUT /v1/workflows/{workflowId}

Update a workflow.

Changing `conditions`, `steps`, or `once_for_template` creates a new workflow version. Changing only `name` or `enabled` updates the workflow row without creating a new version.

### DELETE /v1/workflows/{workflowId}

Delete a workflow.

### GET /v1/workflows/{workflowId}/runs

List recent runs for a workflow.

**Query parameters:**

| name | type | default | description |
|------|------|---------|-------------|
| limit | integer | 50 | Number of runs to return. Values are clamped from 1 to 100. |

### GET /v1/alerts/lifecycles

List alert episodes for the current organization.

**Query parameters:**

| name | type | default | description |
|------|------|---------|-------------|
| status | string | all | Optional episode status, such as `FIRING` or `RESOLVED`. |
| limit | integer | 50 | Number of episodes to return. Values are clamped from 1 to 100. |

### POST /v1/alerts/lifecycles/{episodeId}/ignore

Suppress notifications for the current open alert episode.

**Body:**

| name | type | required | description |
|------|------|----------|-------------|
| reason | string | no | Optional suppress reason. |

### POST /v1/alerts/lifecycles/{episodeId}/unignore

Resume notifications for an alert episode.

## Supported triggers

| name | description |
|------|-------------|
| alert.triggered | Runs when Moneat fires an alert lifecycle event. |
| alert.resolved | Runs when Moneat resolves an alert episode. |

## Supported steps

| name | description |
|------|-------------|
| notification.email_org | Send an email to verified organization members. |
| notification.slack | Post a message to the configured Slack alert channel. |
| notification.discord | Post a message to the configured Discord alert channel. |

Slack and Discord default workflow steps include `skip_if_unconfigured=true`, so organizations without those integrations do not get failed runs from the seeded defaults.

## Interpolation

Step parameters support double-brace interpolation with trigger scope references, for example:

```text
{{alert.title}}
{{alert.display_title}}
{{alert.severity}}
{{alert.priority}}
{{alert.url}}
```

`alert.severity` keeps the internal enum value (`CRITICAL`, `HIGH`, `MEDIUM`, or `LOW`).
`alert.priority` renders the alerting label (`[P0]` through `[P3]`) used in notification titles.

Dashboard alert events also expose dashboard-specific fields such as `alert.dashboard.title`,
`alert.widget.title`, `alert.condition`, `alert.threshold`, and `alert.current_value`.

## Run identity

`once_for_template` is evaluated against the trigger scope to build `workflow_runs.once_for`. Moneat
enforces one run per workflow and `once_for` value, which prevents repeated notification loops.

Firing alert workflows default to `alert.episode_key` and `alert.notification_sequence`, so each
new alert episode can notify again and long-running episodes can send daily reminders. Resolved alert
workflows default to `alert.episode_key` and `alert.status`.

`alert.deduplication_key` remains the stable key for the underlying alert condition. Do not use it
alone as an alert workflow run identity unless repeat episodes should intentionally be suppressed.
