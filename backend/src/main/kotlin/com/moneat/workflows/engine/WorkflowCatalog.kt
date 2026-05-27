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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val EQUALS_LABEL = "is equal to"
private const val NOT_EQUALS_LABEL = "is not equal to"
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
data class WorkflowCatalogResponse(
    val resources: List<WorkflowResourceDefinition>,
    val triggers: List<WorkflowTriggerDefinition>,
    val steps: List<WorkflowStepDefinition>
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
            type = "AlertSeverity",
            label = "Severity",
            fieldConfig = WorkflowFieldConfig(type = "select", placeholder = "CRITICAL, HIGH, MEDIUM, LOW"),
            operations = listOf(
                WorkflowOperationDefinition("eq", EQUALS_LABEL, "AlertSeverity"),
                WorkflowOperationDefinition("neq", NOT_EQUALS_LABEL, "AlertSeverity"),
                WorkflowOperationDefinition("at_least", "is at least", "AlertSeverity")
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
        )
    )

    private val alertScope = listOf(
        WorkflowScopeReferenceDefinition("alert.title", "Alert title", "String"),
        WorkflowScopeReferenceDefinition(ALERT_DISPLAY_TITLE_REFERENCE, "Display title", "String"),
        WorkflowScopeReferenceDefinition("alert.description", "Alert description", "Text"),
        WorkflowScopeReferenceDefinition("alert.severity", "Severity", "AlertSeverity"),
        WorkflowScopeReferenceDefinition(ALERT_PRIORITY_REFERENCE, "Priority", "String"),
        WorkflowScopeReferenceDefinition(ALERT_STATUS_REFERENCE, "Status", "AlertStatus"),
        WorkflowScopeReferenceDefinition("alert.source", "Source", "AlertSource"),
        WorkflowScopeReferenceDefinition(ALERT_DEDUPLICATION_KEY_REFERENCE, "Deduplication key", "String"),
        WorkflowScopeReferenceDefinition("alert.url", "Moneat URL", "String"),
        WorkflowScopeReferenceDefinition(ALERT_DASHBOARD_TITLE_REFERENCE, "Dashboard title", "String"),
        WorkflowScopeReferenceDefinition(ALERT_WIDGET_TITLE_REFERENCE, "Widget title", "String"),
        WorkflowScopeReferenceDefinition(ALERT_CONDITION_REFERENCE, "Condition", "String"),
        WorkflowScopeReferenceDefinition(ALERT_THRESHOLD_REFERENCE, "Threshold", "String"),
        WorkflowScopeReferenceDefinition(ALERT_CURRENT_VALUE_REFERENCE, "Current value", "String"),
        WorkflowScopeReferenceDefinition(ALERT_CHANNEL_EMAIL_REFERENCE, "Email channel", "Boolean"),
        WorkflowScopeReferenceDefinition(ALERT_CHANNEL_SLACK_REFERENCE, "Slack channel", "Boolean"),
        WorkflowScopeReferenceDefinition(ALERT_CHANNEL_DISCORD_REFERENCE, "Discord channel", "Boolean"),
        WorkflowScopeReferenceDefinition("organization.id", "Organization ID", "String")
    )

    val triggers = listOf(
        WorkflowTriggerDefinition(
            name = "alert.triggered",
            label = "When an alert triggers",
            description = "Runs when Moneat fires an alert lifecycle event.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_DEDUPLICATION_KEY_REFERENCE)
        ),
        WorkflowTriggerDefinition(
            name = "alert.resolved",
            label = "When an alert resolves",
            description = "Runs when Moneat resolves an alert by deduplication key.",
            scope = alertScope,
            defaultOnceForTemplate = listOf(ALERT_DEDUPLICATION_KEY_REFERENCE, ALERT_STATUS_REFERENCE)
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
        )
    )

    fun response(): WorkflowCatalogResponse =
        WorkflowCatalogResponse(resources = resources, triggers = triggers, steps = steps)

    fun trigger(name: String): WorkflowTriggerDefinition? = triggers.firstOrNull { it.name == name }

    fun step(name: String): WorkflowStepDefinition? = steps.firstOrNull { it.name == name }

    fun scopeType(triggerName: String, reference: String): String? =
        trigger(triggerName)?.scope?.firstOrNull { it.name == reference }?.type
}
