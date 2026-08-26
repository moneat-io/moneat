// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.workflows.engine

import com.moneat.workflows.engine.temporal.CONNECTOR_GITHUB_CREATE_ISSUE_ACTION
import com.moneat.workflows.engine.temporal.CONNECTOR_JIRA_CREATE_ISSUE_ACTION
import com.moneat.workflows.engine.temporal.CONNECTOR_PAGERDUTY_TRIGGER_INCIDENT_ACTION
import com.moneat.workflows.engine.temporal.CONNECTOR_SERVICENOW_CREATE_INCIDENT_ACTION
import com.moneat.workflows.engine.temporal.HTTP_REQUEST_ACTION
import com.moneat.workflows.engine.temporal.TRANSFORM_GRAALJS_ACTION
import com.moneat.workflows.engine.temporal.WORKFLOW_EGRESS_ACTIONS
import com.moneat.workflows.engine.temporal.workflowEgressActionsEnabled
import com.moneat.workflows.models.ALERT_EPISODE_ID_REFERENCE
import com.moneat.workflows.models.ALERT_EPISODE_KEY_REFERENCE
import com.moneat.workflows.models.ALERT_EPISODE_SEQ_REFERENCE
import com.moneat.workflows.models.ALERT_LAST_SEEN_AT_REFERENCE
import com.moneat.workflows.models.ALERT_NOTIFICATION_KIND_REFERENCE
import com.moneat.workflows.models.ALERT_NOTIFICATION_SEQUENCE_REFERENCE
import com.moneat.workflows.models.ALERT_OPENED_AT_REFERENCE
import com.moneat.workflows.models.INCIDENT_ASSIGNEE_REFERENCE
import com.moneat.workflows.models.INCIDENT_ROLE_ACTION_REFERENCE
import com.moneat.workflows.models.INCIDENT_ROLE_REFERENCE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val EQUALS_LABEL = "is equal to"
private const val NOT_EQUALS_LABEL = "is not equal to"
private const val AT_LEAST_LABEL = "is at least"
private const val ALERT_STATUS_REFERENCE = "alert.status"
private const val ALERT_DEDUPLICATION_KEY_REFERENCE = "alert.deduplication_key"
private const val ALERT_CHANNEL_EMAIL_REFERENCE = "alert.channels.email"
private const val ALERT_CHANNEL_SLACK_REFERENCE = "alert.channels.slack"
private const val ALERT_CHANNEL_DISCORD_REFERENCE = "alert.channels.discord"
private const val ALERT_DISPLAY_TITLE_REFERENCE = "alert.display_title"
private const val ALERT_PRIORITY_REFERENCE = "alert.priority"
private const val ALERT_DASHBOARD_TITLE_REFERENCE = "alert.dashboard.title"
private const val ALERT_WIDGET_TITLE_REFERENCE = "alert.widget.title"
private const val ALERT_CONDITION_REFERENCE = "alert.condition"
private const val ALERT_THRESHOLD_REFERENCE = "alert.threshold"
private const val ALERT_CURRENT_VALUE_REFERENCE = "alert.current_value"
private const val ORGANIZATION_ID_REFERENCE = "organization.id"
private const val WORKFLOW_INPUT_REFERENCE = "workflow.input"
private const val WORKFLOW_ACTOR_ID_REFERENCE = "workflow.actor_id"
private const val WORKFLOW_CALLER_REFERENCE = "workflow.caller"
private const val WEBHOOK_PAYLOAD_REFERENCE = "webhook.payload"
private const val WEBHOOK_EVENT_ID_REFERENCE = "webhook.event_id"
private const val INCIDENT_ID_REFERENCE = "incident.id"
private const val INCIDENT_KIND_REFERENCE = "incident.kind"
private const val INCIDENT_TITLE_REFERENCE = "incident.title"
private const val INCIDENT_STATUS_REFERENCE = "incident.status"
private const val INCIDENT_SEVERITY_REFERENCE = "incident.severity"
private const val SECURITY_RULE_ID_REFERENCE = "security.rule_id"
private const val SECURITY_RULE_NAME_REFERENCE = "security.rule_name"
private const val SECURITY_SEVERITY_REFERENCE = "security.severity"
private const val SECURITY_RESOURCE_REFERENCE = "security.resource"

private const val DEDUPLICATION_KEY_LABEL = "Deduplication key"
private const val INCIDENT_SEVERITY_LABEL = "Incident severity"
private const val ORGANIZATION_ID_LABEL = "Organization ID"
private const val PROJECT_ID_LABEL = "Project ID"

@Serializable
data class WorkflowFieldConfig(
    val type: String,
    val placeholder: String? = null,
    val multiline: Boolean = false
)

@Serializable
data class WorkflowOperationDefinition(
    val name: String,
    val label: String,
    @SerialName("value_type") val valueType: String? = null
)

@Serializable
data class WorkflowResourceDefinition(
    val type: String,
    val label: String,
    @SerialName("field_config") val fieldConfig: WorkflowFieldConfig,
    val operations: List<WorkflowOperationDefinition>
)

@Serializable
data class WorkflowScopeReferenceDefinition(
    val name: String,
    val label: String,
    val type: String,
    val description: String? = null
)

@Serializable
data class WorkflowTriggerDefinition(
    val name: String,
    val label: String,
    val description: String,
    val scope: List<WorkflowScopeReferenceDefinition>,
    @SerialName("default_once_for_template") val defaultOnceForTemplate: List<String>
)

@Serializable
data class WorkflowStepParamDefinition(
    val name: String,
    val label: String,
    val type: String,
    val description: String? = null,
    val required: Boolean = true
)

@Serializable
data class WorkflowStepDefinition(
    val name: String,
    val label: String,
    val description: String,
    val params: List<WorkflowStepParamDefinition>
)

@Serializable
data class WorkflowNodeTypeDefinition(
    val type: String,
    val kind: String? = null,
    val name: String,
    val label: String,
    val description: String,
    val params: List<WorkflowStepParamDefinition> = emptyList(),
    @SerialName("branch_labels") val branchLabels: List<String> = emptyList()
)

@Serializable
data class WorkflowCatalogResponse(
    val resources: List<WorkflowResourceDefinition>,
    val triggers: List<WorkflowTriggerDefinition>,
    val steps: List<WorkflowStepDefinition>,
    @SerialName("node_types") val nodeTypes: List<WorkflowNodeTypeDefinition> = emptyList()
)

object WorkflowCatalog {
    private val stringOperations = listOf(
        WorkflowOperationDefinition("is_set", "is set"),
        WorkflowOperationDefinition("is_not_set", "is not set"),
        WorkflowOperationDefinition("eq", EQUALS_LABEL, "String"),
        WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "String"),
        WorkflowOperationDefinition("contains", "contains", "String"),
        WorkflowOperationDefinition("not_contains", "does not contain", "String")
    )

    val resources = listOf(
        WorkflowResourceDefinition(
            type = "String",
            label = "String",
            fieldConfig = WorkflowFieldConfig(type = "text", placeholder = "Value"),
            operations = stringOperations
        ),
        WorkflowResourceDefinition(
            type = "Text",
            label = "Text",
            fieldConfig = WorkflowFieldConfig(type = "textarea", placeholder = "Message", multiline = true),
            operations = stringOperations
        ),
        WorkflowResourceDefinition(
            type = "Number",
            label = "Number",
            fieldConfig = WorkflowFieldConfig(type = "number", placeholder = "0"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "Number"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "Number"),
                WorkflowOperationDefinition("gt", "is greater than", "Number"),
                WorkflowOperationDefinition("gte", "is greater than or equal to", "Number"),
                WorkflowOperationDefinition("lt", "is less than", "Number"),
                WorkflowOperationDefinition("lte", "is less than or equal to", "Number")
            )
        ),
        WorkflowResourceDefinition(
            type = "Boolean",
            label = "Boolean",
            fieldConfig = WorkflowFieldConfig(type = "select", placeholder = "true, false"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "Boolean"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "Boolean")
            )
        ),
        WorkflowResourceDefinition(
            type = "AlertPriority",
            label = "Alert priority",
            fieldConfig = WorkflowFieldConfig(type = "select", placeholder = "P0, P1, P2, P3, P4, P5"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "AlertPriority"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "AlertPriority"),
                WorkflowOperationDefinition("at_least", AT_LEAST_LABEL, "AlertPriority")
            )
        ),
        WorkflowResourceDefinition(
            type = "IncidentSeverity",
            label = INCIDENT_SEVERITY_LABEL,
            fieldConfig = WorkflowFieldConfig(type = "select", placeholder = "SEV-0, SEV-1, SEV-2, SEV-3, SEV-4"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "IncidentSeverity"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "IncidentSeverity"),
                WorkflowOperationDefinition("at_least", AT_LEAST_LABEL, "IncidentSeverity")
            )
        ),
        WorkflowResourceDefinition(
            type = "AlertSource",
            label = "Alert Source",
            fieldConfig = WorkflowFieldConfig(
                type = "select",
                placeholder = "HOST_ALERT, UPTIME_MONITOR, SYNTHETIC_TEST"
            ),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "AlertSource"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "AlertSource")
            )
        ),
        WorkflowResourceDefinition(
            type = "AlertStatus",
            label = "Alert Status",
            fieldConfig = WorkflowFieldConfig(type = "select", placeholder = "FIRING, RESOLVED"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "AlertStatus"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "AlertStatus")
            )
        ),
        WorkflowResourceDefinition(
            type = "IncidentStatus",
            label = "Incident Status",
            fieldConfig = WorkflowFieldConfig(type = "select", placeholder = "created, updated, resolved"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "IncidentStatus"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "IncidentStatus")
            )
        ),
        WorkflowResourceDefinition(
            type = "SecuritySeverity",
            label = "Security Severity",
            fieldConfig = WorkflowFieldConfig(type = "select", placeholder = "critical, high, medium, low, info"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "SecuritySeverity"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "SecuritySeverity"),
                WorkflowOperationDefinition("at_least", AT_LEAST_LABEL, "SecuritySeverity")
            )
        )
    )

    private val alertScope = listOf(
        WorkflowScopeReferenceDefinition("alert.title", "Alert title", "String"),
        WorkflowScopeReferenceDefinition(ALERT_DISPLAY_TITLE_REFERENCE, "Display title", "String"),
        WorkflowScopeReferenceDefinition("alert.description", "Alert description", "Text"),
        WorkflowScopeReferenceDefinition(ALERT_PRIORITY_REFERENCE, "Priority", "AlertPriority"),
        WorkflowScopeReferenceDefinition(ALERT_STATUS_REFERENCE, "Status", "AlertStatus"),
        WorkflowScopeReferenceDefinition("alert.source", "Source", "AlertSource"),
        WorkflowScopeReferenceDefinition(ALERT_DEDUPLICATION_KEY_REFERENCE, DEDUPLICATION_KEY_LABEL, "String"),
        WorkflowScopeReferenceDefinition(ALERT_EPISODE_ID_REFERENCE, "Episode ID", "String"),
        WorkflowScopeReferenceDefinition(ALERT_EPISODE_KEY_REFERENCE, "Episode key", "String"),
        WorkflowScopeReferenceDefinition(ALERT_EPISODE_SEQ_REFERENCE, "Episode sequence", "String"),
        WorkflowScopeReferenceDefinition(ALERT_NOTIFICATION_SEQUENCE_REFERENCE, "Notification sequence", "String"),
        WorkflowScopeReferenceDefinition(ALERT_NOTIFICATION_KIND_REFERENCE, "Notification kind", "String"),
        WorkflowScopeReferenceDefinition(ALERT_OPENED_AT_REFERENCE, "Opened at", "String"),
        WorkflowScopeReferenceDefinition(ALERT_LAST_SEEN_AT_REFERENCE, "Last seen at", "String"),
        WorkflowScopeReferenceDefinition("alert.url", "Moneat URL", "String"),
        WorkflowScopeReferenceDefinition(ALERT_DASHBOARD_TITLE_REFERENCE, "Dashboard title", "String"),
        WorkflowScopeReferenceDefinition(ALERT_WIDGET_TITLE_REFERENCE, "Widget title", "String"),
        WorkflowScopeReferenceDefinition(ALERT_CONDITION_REFERENCE, "Condition", "String"),
        WorkflowScopeReferenceDefinition(ALERT_THRESHOLD_REFERENCE, "Threshold", "String"),
        WorkflowScopeReferenceDefinition(ALERT_CURRENT_VALUE_REFERENCE, "Current value", "String"),
        WorkflowScopeReferenceDefinition(ALERT_CHANNEL_EMAIL_REFERENCE, "Email channel", "Boolean"),
        WorkflowScopeReferenceDefinition(ALERT_CHANNEL_SLACK_REFERENCE, "Slack channel", "Boolean"),
        WorkflowScopeReferenceDefinition(ALERT_CHANNEL_DISCORD_REFERENCE, "Discord channel", "Boolean"),
        WorkflowScopeReferenceDefinition(ORGANIZATION_ID_REFERENCE, ORGANIZATION_ID_LABEL, "String")
    )

    private val manualScope = listOf(
        WorkflowScopeReferenceDefinition(WORKFLOW_ACTOR_ID_REFERENCE, "Actor user ID", "String"),
        WorkflowScopeReferenceDefinition(WORKFLOW_INPUT_REFERENCE, "Run input", "Text"),
        WorkflowScopeReferenceDefinition(ORGANIZATION_ID_REFERENCE, ORGANIZATION_ID_LABEL, "String")
    )

    private val apiScope = listOf(
        WorkflowScopeReferenceDefinition(WORKFLOW_CALLER_REFERENCE, "Caller", "String"),
        WorkflowScopeReferenceDefinition(WORKFLOW_INPUT_REFERENCE, "API input", "Text"),
        WorkflowScopeReferenceDefinition(ORGANIZATION_ID_REFERENCE, ORGANIZATION_ID_LABEL, "String")
    )

    private val webhookScope = listOf(
        WorkflowScopeReferenceDefinition(WEBHOOK_PAYLOAD_REFERENCE, "Webhook payload", "Text"),
        WorkflowScopeReferenceDefinition(WEBHOOK_EVENT_ID_REFERENCE, "Webhook event ID", "String"),
        WorkflowScopeReferenceDefinition(ORGANIZATION_ID_REFERENCE, ORGANIZATION_ID_LABEL, "String")
    )

    private val incidentScope = listOf(
        WorkflowScopeReferenceDefinition(INCIDENT_ID_REFERENCE, "Incident ID", "String"),
        WorkflowScopeReferenceDefinition(INCIDENT_KIND_REFERENCE, "Incident object type", "String"),
        WorkflowScopeReferenceDefinition(INCIDENT_TITLE_REFERENCE, "Incident title", "String"),
        WorkflowScopeReferenceDefinition(INCIDENT_STATUS_REFERENCE, "Incident status", "IncidentStatus"),
        WorkflowScopeReferenceDefinition(INCIDENT_SEVERITY_REFERENCE, INCIDENT_SEVERITY_LABEL, "IncidentSeverity"),
        WorkflowScopeReferenceDefinition(INCIDENT_ROLE_REFERENCE, "Incident role", "String"),
        WorkflowScopeReferenceDefinition(INCIDENT_ASSIGNEE_REFERENCE, "Role assignee", "String"),
        WorkflowScopeReferenceDefinition(INCIDENT_ROLE_ACTION_REFERENCE, "Role action", "String"),
        WorkflowScopeReferenceDefinition(ALERT_DEDUPLICATION_KEY_REFERENCE, DEDUPLICATION_KEY_LABEL, "String"),
        WorkflowScopeReferenceDefinition(ALERT_EPISODE_ID_REFERENCE, "Episode ID", "String"),
        WorkflowScopeReferenceDefinition(ALERT_EPISODE_KEY_REFERENCE, "Episode key", "String"),
        WorkflowScopeReferenceDefinition(ALERT_EPISODE_SEQ_REFERENCE, "Episode sequence", "String"),
        WorkflowScopeReferenceDefinition(ALERT_NOTIFICATION_SEQUENCE_REFERENCE, "Notification sequence", "String"),
        WorkflowScopeReferenceDefinition(ALERT_NOTIFICATION_KIND_REFERENCE, "Notification kind", "String"),
        WorkflowScopeReferenceDefinition(ALERT_OPENED_AT_REFERENCE, "Opened at", "String"),
        WorkflowScopeReferenceDefinition(ALERT_LAST_SEEN_AT_REFERENCE, "Last seen at", "String"),
        WorkflowScopeReferenceDefinition(ORGANIZATION_ID_REFERENCE, ORGANIZATION_ID_LABEL, "String")
    )

    private val securityScope = listOf(
        WorkflowScopeReferenceDefinition(SECURITY_RULE_ID_REFERENCE, "Rule ID", "String"),
        WorkflowScopeReferenceDefinition(SECURITY_RULE_NAME_REFERENCE, "Rule name", "String"),
        WorkflowScopeReferenceDefinition(SECURITY_SEVERITY_REFERENCE, "Severity", "SecuritySeverity"),
        WorkflowScopeReferenceDefinition(SECURITY_RESOURCE_REFERENCE, "Resource", "String"),
        WorkflowScopeReferenceDefinition(ORGANIZATION_ID_REFERENCE, ORGANIZATION_ID_LABEL, "String")
    )

    val triggers = listOf(
        WorkflowTriggerDefinition(
            name = "alert.triggered",
            label = "When an alert triggers",
            description = "Runs when Moneat fires an alert lifecycle event.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_NOTIFICATION_SEQUENCE_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "alert.resolved",
            label = "When an alert resolves",
            description = "Runs when Moneat resolves an alert episode.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_STATUS_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "monitor.alerted",
            label = "When a monitor alerts",
            description = "Runs when a host or dashboard monitor fires.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_NOTIFICATION_SEQUENCE_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "monitor.recovered",
            label = "When a monitor recovers",
            description = "Runs when a host or dashboard monitor recovers.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_STATUS_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "uptime.down",
            label = "When uptime monitor is down",
            description = "Runs when an uptime monitor reports an outage.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_NOTIFICATION_SEQUENCE_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "uptime.up",
            label = "When uptime monitor recovers",
            description = "Runs when an uptime monitor returns to service.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_STATUS_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "synthetic.failed",
            label = "When a synthetic test fails",
            description = "Runs when a synthetic test reports a failed run.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_NOTIFICATION_SEQUENCE_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "synthetic.passed",
            label = "When a synthetic test passes",
            description = "Runs when a synthetic test recovers after failures.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_STATUS_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "incident.created",
            label = "When an incident is created",
            description = "Runs when incident routing creates or pages a response event.",
            scope = incidentScope,
            defaultOnceForTemplate = listOf(INCIDENT_ID_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "incident.resolved",
            label = "When an incident resolves",
            description = "Runs when incident routing resolves a response event.",
            scope = incidentScope,
            defaultOnceForTemplate = listOf(INCIDENT_ID_REFERENCE, INCIDENT_STATUS_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "incident.role_changed",
            label = "When an incident role changes",
            description = "Runs when an incident role is assigned, claimed, handed over, or unassigned.",
            scope = incidentScope,
            defaultOnceForTemplate = listOf(INCIDENT_ID_REFERENCE, INCIDENT_ROLE_ACTION_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "security.signal",
            label = "When a security signal arrives",
            description = "Runs when runtime or compliance security telemetry is ingested.",
            scope = securityScope,
            defaultOnceForTemplate = listOf(SECURITY_RULE_ID_REFERENCE, SECURITY_RESOURCE_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "manual",
            label = "Run manually",
            description = "Runs when an administrator starts the workflow from the dashboard.",
            scope = manualScope,
            defaultOnceForTemplate = emptyList()
        ),
        WorkflowTriggerDefinition(
            name = "api",
            label = "Run from API",
            description = "Runs when an authenticated API caller starts a workflow instance.",
            scope = apiScope,
            defaultOnceForTemplate = emptyList()
        ),
        WorkflowTriggerDefinition(
            name = "webhook",
            label = "Run from signed webhook",
            description = "Runs when Moneat receives a valid signed inbound webhook.",
            scope = webhookScope,
            defaultOnceForTemplate = listOf(WEBHOOK_EVENT_ID_REFERENCE)
        )
    )

    val steps = listOf(
        WorkflowStepDefinition(
            name = "notification.email_org",
            label = "Email organization members",
            description = "Send a workflow email to verified members in the organization.",
            params = listOf(
                WorkflowStepParamDefinition("subject", "Subject", "String"),
                WorkflowStepParamDefinition("body", "Body", "Text")
            )
        ),
        WorkflowStepDefinition(
            name = "notification.slack",
            label = "Post to Slack",
            description = "Post a message to the configured Slack alert channel.",
            params = listOf(
                WorkflowStepParamDefinition("message", "Message", "Text")
            )
        ),
        WorkflowStepDefinition(
            name = "notification.discord",
            label = "Post to Discord",
            description = "Post a message to the configured Discord alert channel.",
            params = listOf(
                WorkflowStepParamDefinition("title", "Title", "String", required = false),
                WorkflowStepParamDefinition("message", "Message", "Text")
            )
        ),
        WorkflowStepDefinition(
            name = "moneat.logs.search",
            label = "Search logs",
            description = "Search organization logs with the same query path used by Moneat log management.",
            params = listOf(
                WorkflowStepParamDefinition("query", "Query", "String", required = false),
                WorkflowStepParamDefinition("levels", "Levels", "String", "Comma-separated log levels", false),
                WorkflowStepParamDefinition("service", "Service", "String", required = false),
                WorkflowStepParamDefinition("environment", "Environment", "String", required = false),
                WorkflowStepParamDefinition("from", "From", "String", "ISO-8601 start time", false),
                WorkflowStepParamDefinition("to", "To", "String", "ISO-8601 end time", false),
                WorkflowStepParamDefinition("limit", "Limit", "Number", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "moneat.logs.aggregate",
            label = "Aggregate logs",
            description = "Aggregate log volume by interval and optional group.",
            params = listOf(
                WorkflowStepParamDefinition("query", "Query", "String", required = false),
                WorkflowStepParamDefinition("levels", "Levels", "String", "Comma-separated log levels", false),
                WorkflowStepParamDefinition("service", "Service", "String", required = false),
                WorkflowStepParamDefinition("from", "From", "String", "ISO-8601 start time", false),
                WorkflowStepParamDefinition("to", "To", "String", "ISO-8601 end time", false),
                WorkflowStepParamDefinition("interval", "Interval", "String", "1m, 5m, 15m, 1h, or 1d", false),
                WorkflowStepParamDefinition("group_by", "Group by", "String", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "moneat.metrics.query",
            label = "Query host metrics",
            description = "Read historical host metrics for enrichment.",
            params = listOf(
                WorkflowStepParamDefinition("host_id", "Host ID", "String"),
                WorkflowStepParamDefinition("hours", "Hours", "Number", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "moneat.traces.search",
            label = "Search traces",
            description = "Read recent transaction and trace summaries for a project.",
            params = listOf(
                WorkflowStepParamDefinition("project_id", PROJECT_ID_LABEL, "String"),
                WorkflowStepParamDefinition("period", "Period", "String", "1h, 6h, 24h, 7d, or 30d", false),
                WorkflowStepParamDefinition("environment", "Environment", "String", required = false),
                WorkflowStepParamDefinition("operation", "Operation", "String", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "moneat.span.get",
            label = "Get span",
            description = "Read details for a single span in an organization project.",
            params = listOf(
                WorkflowStepParamDefinition("project_id", PROJECT_ID_LABEL, "String"),
                WorkflowStepParamDefinition("span_id", "Span ID", "String")
            )
        ),
        WorkflowStepDefinition(
            name = "moneat.issues.list",
            label = "List issues",
            description = "List issues in an organization project.",
            params = listOf(
                WorkflowStepParamDefinition("project_id", PROJECT_ID_LABEL, "String"),
                WorkflowStepParamDefinition("status", "Status", "String", required = false),
                WorkflowStepParamDefinition("page", "Page", "Number", required = false),
                WorkflowStepParamDefinition("limit", "Limit", "Number", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "moneat.issues.get",
            label = "Get issue",
            description = "Read issue details in an organization project.",
            params = listOf(
                WorkflowStepParamDefinition("project_id", PROJECT_ID_LABEL, "String"),
                WorkflowStepParamDefinition("issue_id", "Issue ID", "String")
            )
        ),
        WorkflowStepDefinition(
            name = "statuspage.update",
            label = "Update status page",
            description = "Update safe status page fields.",
            params = listOf(
                WorkflowStepParamDefinition("status_page_id", "Status page ID", "String"),
                WorkflowStepParamDefinition("name", "Name", "String", required = false),
                WorkflowStepParamDefinition("description", "Description", "Text", required = false),
                WorkflowStepParamDefinition("is_public", "Public", "Boolean", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "statuspage.incident.create",
            label = "Create status incident",
            description = "Create a status page incident update for customer communication.",
            params = listOf(
                WorkflowStepParamDefinition("status_page_id", "Status page ID", "String"),
                WorkflowStepParamDefinition("title", "Title", "String"),
                WorkflowStepParamDefinition("message", "Message", "Text"),
                WorkflowStepParamDefinition("status", "Status", "String", required = false),
                WorkflowStepParamDefinition("impact", "Impact", "String", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "alert.silence",
            label = "Silence alerts",
            description = "Create an organization alert silence period.",
            params = listOf(
                WorkflowStepParamDefinition("reason", "Reason", "String", required = false),
                WorkflowStepParamDefinition("starts_at", "Starts at", "Number", "Epoch milliseconds", false),
                WorkflowStepParamDefinition("ends_at", "Ends at", "Number", "Epoch milliseconds", false)
            )
        ),
        WorkflowStepDefinition(
            name = "oncall.page",
            label = "Page on-call",
            description = "Page responders through the on-call bridge when Enterprise on-call is enabled.",
            params = listOf(
                WorkflowStepParamDefinition("escalation_policy_id", "Escalation policy ID", "String"),
                WorkflowStepParamDefinition("title", "Title", "String"),
                WorkflowStepParamDefinition("description", "Description", "Text", required = false),
                WorkflowStepParamDefinition("alert_priority", "Alert priority", "AlertPriority", required = false),
                WorkflowStepParamDefinition("deduplication_key", DEDUPLICATION_KEY_LABEL, "String", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = "oncall.incident.declare",
            label = "Declare incident",
            description = "Declare an operational incident through the on-call bridge.",
            params = listOf(
                WorkflowStepParamDefinition("title", "Title", "String"),
                WorkflowStepParamDefinition("description", "Description", "Text", required = false),
                WorkflowStepParamDefinition("incident_severity", INCIDENT_SEVERITY_LABEL, "IncidentSeverity"),
                WorkflowStepParamDefinition("alert_id", "Alert ID", "String", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = HTTP_REQUEST_ACTION,
            label = "HTTP request",
            description = "Call an external HTTP endpoint from the isolated egress worker.",
            params = listOf(
                WorkflowStepParamDefinition("url", "URL", "String"),
                WorkflowStepParamDefinition("method", "Method", "String", required = false),
                WorkflowStepParamDefinition("headers", "Headers", "Text", "JSON object of allowed headers", false),
                WorkflowStepParamDefinition("body", "Body", "Text", required = false),
                WorkflowStepParamDefinition("timeout_seconds", "Timeout seconds", "Number", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = TRANSFORM_GRAALJS_ACTION,
            label = "Transform with JavaScript",
            description = "Run a bounded GraalJS transform in the isolated egress worker.",
            params = listOf(
                WorkflowStepParamDefinition("script", "Script", "Text"),
                WorkflowStepParamDefinition("timeout_seconds", "Timeout seconds", "Number", required = false)
            )
        ),
        WorkflowStepDefinition(
            name = CONNECTOR_JIRA_CREATE_ISSUE_ACTION,
            label = "Create Jira issue",
            description = "Create a Jira issue using an Enterprise workflow connection.",
            params = connectorParams("jira")
        ),
        WorkflowStepDefinition(
            name = CONNECTOR_PAGERDUTY_TRIGGER_INCIDENT_ACTION,
            label = "Trigger PagerDuty incident",
            description = "Trigger a PagerDuty incident using an Enterprise workflow connection.",
            params = connectorParams("pagerduty")
        ),
        WorkflowStepDefinition(
            name = CONNECTOR_GITHUB_CREATE_ISSUE_ACTION,
            label = "Create GitHub issue",
            description = "Create a GitHub issue using an Enterprise workflow connection.",
            params = connectorParams("github")
        ),
        WorkflowStepDefinition(
            name = CONNECTOR_SERVICENOW_CREATE_INCIDENT_ACTION,
            label = "Create ServiceNow incident",
            description = "Create a ServiceNow incident using an Enterprise workflow connection.",
            params = connectorParams("servicenow")
        )
    )

    val nodeTypes = listOf(
        WorkflowNodeTypeDefinition(
            type = "trigger",
            name = "trigger",
            label = "Trigger",
            description = "Starts a workflow from a telemetry event."
        ),
        WorkflowNodeTypeDefinition(
            type = "condition",
            kind = "if",
            name = "condition.if",
            label = "If / else",
            description = "Routes execution based on a set of conditions.",
            branchLabels = listOf("true", "false")
        ),
        WorkflowNodeTypeDefinition(
            type = "condition",
            kind = "switch",
            name = "condition.switch",
            label = "Switch",
            description = "Routes execution to the first matching case.",
            branchLabels = listOf("case", "default")
        ),
        WorkflowNodeTypeDefinition(
            type = "control",
            kind = "sleep",
            name = "control.sleep",
            label = "Sleep",
            description = "Pauses execution for a bounded duration.",
            params = listOf(
                WorkflowStepParamDefinition("duration", "Duration", "String", "ISO-8601 duration, for example PT5M")
            )
        ),
        WorkflowNodeTypeDefinition(
            type = "control",
            kind = "wait_until",
            name = "control.wait_until",
            label = "Wait until",
            description = "Waits until conditions match or a timeout is reached.",
            params = listOf(
                WorkflowStepParamDefinition("timeout", "Timeout", "String", "ISO-8601 duration, for example PT30M")
            ),
            branchLabels = listOf("true", "timeout")
        ),
        WorkflowNodeTypeDefinition(
            type = "control",
            kind = "for_each",
            name = "control.for_each",
            label = "For each",
            description = "Runs a branch once per item from a scoped list.",
            params = listOf(
                WorkflowStepParamDefinition("items_reference", "Items reference", "String"),
                WorkflowStepParamDefinition("item_variable", "Item variable", "String", required = false),
                WorkflowStepParamDefinition("max_items", "Maximum items", "Number", required = false)
            ),
            branchLabels = listOf("body", "done")
        ),
        WorkflowNodeTypeDefinition(
            type = "control",
            kind = "while",
            name = "control.while",
            label = "While",
            description = "Repeats a branch while conditions match, with a hard cap.",
            params = listOf(
                WorkflowStepParamDefinition("max_iterations", "Maximum iterations", "Number", required = false)
            ),
            branchLabels = listOf("body", "done")
        ),
        WorkflowNodeTypeDefinition(
            type = "control",
            kind = "approval",
            name = "control.approval",
            label = "Approval",
            description = "Pause the workflow until an authorized approver accepts, rejects, or the timeout expires.",
            params = listOf(
                WorkflowStepParamDefinition("message", "Message", "Text"),
                WorkflowStepParamDefinition("approver_role", "Approver role", "String", required = false),
                WorkflowStepParamDefinition("timeout", "Timeout", "String", "ISO-8601 duration, for example PT24H")
            ),
            branchLabels = listOf("approved", "rejected", "timeout")
        )
    )

    fun response(): WorkflowCatalogResponse {
        val visibleSteps =
            if (workflowEgressActionsEnabled()) steps else steps.filterNot { it.name in WORKFLOW_EGRESS_ACTIONS }
        return WorkflowCatalogResponse(
            resources = resources,
            triggers = triggers,
            steps = visibleSteps,
            nodeTypes = nodeTypes
        )
    }

    fun trigger(name: String): WorkflowTriggerDefinition? = triggers.firstOrNull { it.name == name }

    fun step(name: String): WorkflowStepDefinition? = steps.firstOrNull { it.name == name }

    fun scopeType(triggerName: String, reference: String): String? =
        trigger(triggerName)?.scope?.firstOrNull { it.name == reference }?.type

    private fun connectorParams(connectionType: String): List<WorkflowStepParamDefinition> =
        listOf(
            WorkflowStepParamDefinition("connection_id", "$connectionType connection ID", "String", required = false),
            WorkflowStepParamDefinition(
                "connection_group_id",
                "$connectionType connection group ID",
                "String",
                required = false
            ),
            WorkflowStepParamDefinition("title", "Title", "String"),
            WorkflowStepParamDefinition("description", "Description", "Text", required = false),
            WorkflowStepParamDefinition("metadata", "Metadata", "Text", "Connector-specific JSON payload", false)
        )
}
