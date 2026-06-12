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

package com.moneat.workflows.services

import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.IncidentSeverity
import com.moneat.alerts.models.AlertStatus
import com.moneat.config.EnvConfig
import com.moneat.workflows.models.ALERT_EPISODE_ID_REFERENCE
import com.moneat.workflows.models.ALERT_EPISODE_KEY_REFERENCE
import com.moneat.workflows.models.ALERT_EPISODE_SEQ_REFERENCE
import com.moneat.workflows.models.ALERT_LAST_SEEN_AT_REFERENCE
import com.moneat.workflows.models.ALERT_NOTIFICATION_KIND_REFERENCE
import com.moneat.workflows.models.ALERT_NOTIFICATION_SEQUENCE_REFERENCE
import com.moneat.workflows.models.ALERT_OPENED_AT_REFERENCE
import com.moneat.workflows.models.WorkflowPreviewField
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowStepPreview

internal const val ALERT_TITLE_REFERENCE = "alert.title"
internal const val ALERT_DESCRIPTION_REFERENCE = "alert.description"
internal const val ALERT_PRIORITY_REFERENCE = "alert.priority"
internal const val ALERT_STATUS_REFERENCE = "alert.status"
internal const val ALERT_SOURCE_REFERENCE = "alert.source"
internal const val ALERT_DEDUPLICATION_KEY_REFERENCE = "alert.deduplication_key"
internal const val ALERT_URL_REFERENCE = "alert.url"
internal const val ALERT_CHANNEL_EMAIL_REFERENCE = "alert.channels.email"
internal const val ALERT_CHANNEL_SLACK_REFERENCE = "alert.channels.slack"
internal const val ALERT_CHANNEL_DISCORD_REFERENCE = "alert.channels.discord"
internal const val ORGANIZATION_ID_REFERENCE = "organization.id"
internal const val EMAIL_ORG_STEP = "notification.email_org"
internal const val SLACK_STEP = "notification.slack"
internal const val DISCORD_STEP = "notification.discord"
internal const val EMAIL_CHANNEL = "email"
internal const val SLACK_CHANNEL = "slack"
internal const val DISCORD_CHANNEL = "discord"
internal const val SKIP_IF_UNCONFIGURED_PARAM = "skip_if_unconfigured"
internal const val FORMAT_PARAM = "format"
internal const val ALERT_LIFECYCLE_FORMAT = "alert_lifecycle"
internal const val DEFAULT_WORKFLOW_TITLE = "Moneat workflow"
internal const val ALERT_TRIGGERED_TRIGGER = "alert.triggered"
internal const val ALERT_RESOLVED_TRIGGER = "alert.resolved"
internal const val MANUAL_TRIGGER = "manual"
internal const val API_TRIGGER = "api"
internal const val WEBHOOK_TRIGGER = "webhook"
internal const val INCIDENT_CREATED_TRIGGER = "incident.created"
internal const val INCIDENT_RESOLVED_TRIGGER = "incident.resolved"
internal const val SECURITY_SIGNAL_TRIGGER = "security.signal"
internal const val ALERT_DESCRIPTION_TEMPLATE_BLOCK = "{{alert.description}}\n\n"
internal const val ALERT_PRIORITY_TEMPLATE_LINE = "Priority: {{alert.priority}}\n"
internal const val ALERT_SOURCE_TEMPLATE_LINE = "Source: {{alert.source}}\n"
internal const val ALERT_URL_TEMPLATE = "{{alert.url}}"
internal const val VIEW_CTA_LABEL = "View"

internal const val ALERT_DISPLAY_TITLE_REFERENCE = "alert.display_title"
internal const val ALERT_DASHBOARD_TITLE_REFERENCE = "alert.dashboard.title"
internal const val ALERT_WIDGET_TITLE_REFERENCE = "alert.widget.title"
internal const val ALERT_CONDITION_REFERENCE = "alert.condition"
internal const val ALERT_THRESHOLD_REFERENCE = "alert.threshold"
internal const val ALERT_CURRENT_VALUE_REFERENCE = "alert.current_value"
private const val ALERT_COLOR_RED = "#E01E5A"
private const val ALERT_COLOR_GREEN = "#2EB67D"
private const val ALERT_COLOR_YELLOW = "#ECB22E"
private const val ALERT_COLOR_PURPLE = "#6366F1"
private const val SAMPLE_ORGANIZATION_ID = "123e4567-e89b-12d3-a456-426614170001"
private const val SAMPLE_USER_ID = "123e4567-e89b-12d3-a456-426614170002"
private const val SAMPLE_ALERT_EPISODE_ID = "123e4567-e89b-12d3-a456-426614170003"
private const val SAMPLE_INCIDENT_ID = "123e4567-e89b-12d3-a456-426614170004"
private const val SAMPLE_SECURITY_RULE_ID = "123e4567-e89b-12d3-a456-426614170005"
private val RESOLVED_ALERT_TRIGGERS =
    setOf(ALERT_RESOLVED_TRIGGER, "monitor.recovered", "uptime.up", "synthetic.passed")

class WorkflowStepRenderer {
    fun sampleScopeForTrigger(triggerName: String): Map<String, String> =
        when (triggerName) {
            MANUAL_TRIGGER -> manualSampleScope()
            API_TRIGGER -> apiSampleScope()
            WEBHOOK_TRIGGER -> webhookSampleScope()
            INCIDENT_CREATED_TRIGGER,
            INCIDENT_RESOLVED_TRIGGER -> incidentSampleScope(triggerName)
            SECURITY_SIGNAL_TRIGGER -> securitySampleScope()
            else -> alertSampleScope(triggerName)
        }

    private fun alertSampleScope(triggerName: String): Map<String, String> {
        val resolved = triggerName in RESOLVED_ALERT_TRIGGERS
        val status = if (resolved) AlertStatus.RESOLVED.name else AlertStatus.FIRING.name
        val priority = if (resolved) AlertPriority.P3.wire else AlertPriority.P1.wire
        val title = if (resolved) {
            "Dashboard Alert Resolved: Worker failures detected"
        } else {
            "Dashboard Error: Worker failures detected"
        }
        val description = if (resolved) {
            "Worker failures [1h] on Moneat Backend System Health recovered. Current value: 0.00"
        } else {
            "Worker failures [1h] on Moneat Backend System Health crossed > 5.00. Current value: 12.00"
        }
        val currentValue = if (resolved) "0.00" else "12.00"
        return mapOf(
            ALERT_TITLE_REFERENCE to title,
            ALERT_DISPLAY_TITLE_REFERENCE to "Worker failures detected",
            ALERT_DESCRIPTION_REFERENCE to description,
            ALERT_PRIORITY_REFERENCE to priority,
            ALERT_STATUS_REFERENCE to status,
            ALERT_SOURCE_REFERENCE to "DASHBOARD_ALERT",
            ALERT_DEDUPLICATION_KEY_REFERENCE to "moneat-dashboard-alert-preview",
            ALERT_EPISODE_ID_REFERENCE to SAMPLE_ALERT_EPISODE_ID,
            ALERT_EPISODE_KEY_REFERENCE to "moneat-dashboard-alert-preview#3",
            ALERT_EPISODE_SEQ_REFERENCE to "3",
            ALERT_NOTIFICATION_SEQUENCE_REFERENCE to "1",
            ALERT_NOTIFICATION_KIND_REFERENCE to if (resolved) "resolved" else "initial",
            ALERT_OPENED_AT_REFERENCE to "2026-06-02T12:00:00Z",
            ALERT_LAST_SEEN_AT_REFERENCE to "2026-06-02T12:05:00Z",
            ALERT_URL_REFERENCE to "https://moneat.io/dashboards/13",
            ALERT_DASHBOARD_TITLE_REFERENCE to "Moneat Backend System Health",
            ALERT_WIDGET_TITLE_REFERENCE to "Worker failures [1h]",
            ALERT_CONDITION_REFERENCE to ">",
            ALERT_THRESHOLD_REFERENCE to "5.00",
            ALERT_CURRENT_VALUE_REFERENCE to currentValue,
            ALERT_CHANNEL_EMAIL_REFERENCE to "true",
            ALERT_CHANNEL_SLACK_REFERENCE to "true",
            ALERT_CHANNEL_DISCORD_REFERENCE to "true",
            ORGANIZATION_ID_REFERENCE to SAMPLE_ORGANIZATION_ID
        )
    }

    private fun manualSampleScope(): Map<String, String> =
        mapOf(
            "workflow.actor_id" to SAMPLE_USER_ID,
            "workflow.input" to """{"reason":"Preview run"}""",
            ORGANIZATION_ID_REFERENCE to SAMPLE_ORGANIZATION_ID
        )

    private fun apiSampleScope(): Map<String, String> =
        mapOf(
            "workflow.caller" to SAMPLE_USER_ID,
            "workflow.input" to """{"service":"checkout"}""",
            ORGANIZATION_ID_REFERENCE to SAMPLE_ORGANIZATION_ID
        )

    private fun webhookSampleScope(): Map<String, String> =
        mapOf(
            "webhook.payload" to """{"event":"deploy.finished","service":"checkout"}""",
            "webhook.event_id" to "deploy-evt-123",
            ORGANIZATION_ID_REFERENCE to SAMPLE_ORGANIZATION_ID
        )

    private fun incidentSampleScope(triggerName: String): Map<String, String> {
        val status = when (triggerName) {
            INCIDENT_RESOLVED_TRIGGER -> "resolved"
            else -> "created"
        }
        return mapOf(
            "incident.id" to SAMPLE_INCIDENT_ID,
            "incident.title" to "Checkout latency incident",
            "incident.status" to status,
            "incident.severity" to IncidentSeverity.SEV1.wire,
            ALERT_DEDUPLICATION_KEY_REFERENCE to "incident-checkout-latency",
            ALERT_EPISODE_ID_REFERENCE to SAMPLE_INCIDENT_ID,
            ALERT_EPISODE_KEY_REFERENCE to "incident-checkout-latency#3",
            ALERT_EPISODE_SEQ_REFERENCE to "3",
            ALERT_NOTIFICATION_SEQUENCE_REFERENCE to "1",
            ALERT_NOTIFICATION_KIND_REFERENCE to status,
            ALERT_OPENED_AT_REFERENCE to "2026-06-02T12:00:00Z",
            ALERT_LAST_SEEN_AT_REFERENCE to "2026-06-02T12:05:00Z",
            ORGANIZATION_ID_REFERENCE to SAMPLE_ORGANIZATION_ID
        )
    }

    private fun securitySampleScope(): Map<String, String> =
        mapOf(
            "security.rule_id" to SAMPLE_SECURITY_RULE_ID,
            "security.rule_name" to "Sensitive file modified",
            "security.severity" to "high",
            "security.resource" to "/etc/app/config.yml",
            ORGANIZATION_ID_REFERENCE to SAMPLE_ORGANIZATION_ID
        )

    fun renderStepPreview(
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): WorkflowStepPreview =
        if (step.usesAlertLifecycleFormat()) {
            renderAlertLifecyclePreview(step, scope)
        } else {
            renderFreeformStepPreview(step, scope)
        }

    fun channelForStep(stepName: String): String =
        when (stepName) {
            EMAIL_ORG_STEP -> EMAIL_CHANNEL
            SLACK_STEP -> SLACK_CHANNEL
            DISCORD_STEP -> DISCORD_CHANNEL
            else -> "workflow"
        }

    fun priorityLabel(priority: String?): String =
        AlertPriority.fromString(priority)?.wire.orEmpty()

    private fun renderFreeformStepPreview(
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): WorkflowStepPreview =
        when (step.name) {
            EMAIL_ORG_STEP -> {
                val subject = interpolate(step.params["subject"] ?: "Moneat workflow: {{alert.title}}", scope)
                val body = interpolate(step.params["body"].orEmpty(), scope)
                WorkflowStepPreview(
                    step = step.name,
                    channel = EMAIL_CHANNEL,
                    title = subject,
                    subject = subject,
                    body = body,
                    htmlBody = body.preformattedHtml(),
                    textBody = body,
                    color = ALERT_COLOR_PURPLE,
                    fallbackText = "$subject: $body"
                )
            }
            SLACK_STEP -> {
                val body = interpolate(step.params["message"].orEmpty(), scope)
                WorkflowStepPreview(
                    step = step.name,
                    channel = SLACK_CHANNEL,
                    title = DEFAULT_WORKFLOW_TITLE,
                    body = body,
                    textBody = body,
                    color = ALERT_COLOR_PURPLE,
                    fallbackText = body
                )
            }
            DISCORD_STEP -> {
                val title = interpolate(step.params["title"] ?: DEFAULT_WORKFLOW_TITLE, scope)
                val body = interpolate(step.params["message"].orEmpty(), scope)
                WorkflowStepPreview(
                    step = step.name,
                    channel = DISCORD_CHANNEL,
                    title = title,
                    body = body,
                    textBody = body,
                    color = ALERT_COLOR_PURPLE,
                    footer = DEFAULT_WORKFLOW_TITLE,
                    fallbackText = "$title: $body"
                )
            }
            else -> WorkflowStepPreview(
                step = step.name,
                channel = "workflow",
                title = step.name,
                body = "This action reads or updates Moneat data when the workflow runs.",
                textBody = "This action reads or updates Moneat data when the workflow runs.",
                color = ALERT_COLOR_PURPLE,
                fallbackText = step.name
            )
        }

    private fun renderAlertLifecyclePreview(
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): WorkflowStepPreview {
        val channel = channelForStep(step.name)
        val displayTitle = alertDisplayTitle(scope)
        val title = alertLifecycleTitle(displayTitle, scope)
        val body = scope[ALERT_DESCRIPTION_REFERENCE]?.ifBlank { null } ?: "Moneat detected an alert lifecycle event."
        val fields = alertLifecycleFields(scope)
        val color = alertLifecycleColor(scope)
        val ctaUrl = scope[ALERT_URL_REFERENCE]?.takeIf { it.isNotBlank() }
        val ctaLabel = ctaUrl?.let { VIEW_CTA_LABEL }
        val sourceLabel = sourceLabel(scope[ALERT_SOURCE_REFERENCE])
        val textBody = buildAlertText(title, body, fields, ctaLabel, ctaUrl)
        val preview =
            WorkflowStepPreview(
                step = step.name,
                channel = channel,
                title = title,
                subject = if (channel == EMAIL_CHANNEL) "[Moneat] $title" else null,
                body = body,
                textBody = textBody,
                fields = fields,
                color = color,
                ctaLabel = ctaLabel,
                ctaUrl = ctaUrl,
                footer = sourceLabel,
                fallbackText = "$title: $body"
            )
        return if (channel == EMAIL_CHANNEL) {
            preview.copy(htmlBody = buildAlertLifecycleHtml(preview))
        } else {
            preview
        }
    }

    private fun alertDisplayTitle(scope: Map<String, String>): String =
        scope[ALERT_DISPLAY_TITLE_REFERENCE]?.takeIf { it.isNotBlank() }
            ?: cleanAlertTitle(scope[ALERT_TITLE_REFERENCE], scope[ALERT_STATUS_REFERENCE])

    private fun alertLifecycleTitle(
        displayTitle: String,
        scope: Map<String, String>
    ): String {
        val priority = priorityLabel(scope)
        if (scope[ALERT_STATUS_REFERENCE] == AlertStatus.RESOLVED.name) {
            val prefix = listOf(priority, "Resolved").filter { it.isNotBlank() }.joinToString(" ")
            return if (prefix.isBlank()) displayTitle else "$prefix: $displayTitle"
        }
        return listOf(priority, displayTitle).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun cleanAlertTitle(
        title: String?,
        status: String?
    ): String {
        val prefixes =
            if (status == AlertStatus.RESOLVED.name) {
                listOf("Resolved: ", "Dashboard Alert Resolved: ")
            } else {
                listOf("Dashboard Critical: ", "Dashboard Error: ", "Dashboard Warning: ", "Dashboard Alert: ")
            }
        return prefixes.fold(title.orEmpty()) { current, prefix ->
            current.removePrefix(prefix)
        }.ifBlank { "Moneat alert" }
    }

    private fun alertLifecycleFields(scope: Map<String, String>): List<WorkflowPreviewField> {
        val fields = mutableListOf<WorkflowPreviewField>()
        addField(fields, "Status", humanizeEnum(scope[ALERT_STATUS_REFERENCE]))
        addField(fields, "Priority", priorityLabel(scope))
        addField(fields, "Source", sourceLabel(scope[ALERT_SOURCE_REFERENCE]))
        addField(fields, "Dashboard", scope[ALERT_DASHBOARD_TITLE_REFERENCE])
        addField(fields, "Widget", scope[ALERT_WIDGET_TITLE_REFERENCE])
        addField(fields, "Current value", scope[ALERT_CURRENT_VALUE_REFERENCE])
        addField(fields, "Threshold", thresholdText(scope))
        return fields
    }

    private fun addField(
        fields: MutableList<WorkflowPreviewField>,
        label: String,
        value: String?
    ) {
        if (!value.isNullOrBlank()) {
            fields += WorkflowPreviewField(label, value)
        }
    }

    private fun thresholdText(scope: Map<String, String>): String? {
        val condition = scope[ALERT_CONDITION_REFERENCE]?.takeIf { it.isNotBlank() }
        val threshold = scope[ALERT_THRESHOLD_REFERENCE]?.takeIf { it.isNotBlank() }
        return when {
            condition != null && threshold != null -> "$condition $threshold"
            threshold != null -> threshold
            else -> null
        }
    }

    private fun alertLifecycleColor(scope: Map<String, String>): String {
        if (scope[ALERT_STATUS_REFERENCE] == AlertStatus.RESOLVED.name) return ALERT_COLOR_GREEN
        val priority = AlertPriority.fromString(scope[ALERT_PRIORITY_REFERENCE])
        return if (priority in setOf(AlertPriority.P0, AlertPriority.P1)) {
            ALERT_COLOR_RED
        } else {
            ALERT_COLOR_YELLOW
        }
    }

    private fun priorityLabel(scope: Map<String, String>): String =
        priorityLabel(scope[ALERT_PRIORITY_REFERENCE]).takeIf { it.isNotBlank() }
            ?: scope[ALERT_PRIORITY_REFERENCE].orEmpty()

    private fun sourceLabel(source: String?): String =
        when (source) {
            "DASHBOARD_ALERT" -> "Dashboard alert"
            "HOST_ALERT" -> "Host metric alert"
            "HOST_DOWN" -> "Host down"
            "UPTIME_MONITOR" -> "Uptime monitor"
            "SYNTHETIC_TEST" -> "Synthetic test"
            "ERROR_ALERT" -> "Error issue"
            else -> humanizeEnum(source).ifBlank { "Moneat alert" }
        }

    private fun buildAlertText(
        title: String,
        body: String,
        fields: List<WorkflowPreviewField>,
        ctaLabel: String?,
        ctaUrl: String?
    ): String =
        buildString {
            appendLine(title)
            appendLine()
            appendLine(body)
            if (fields.isNotEmpty()) {
                appendLine()
                fields.forEach { field -> appendLine("${field.label}: ${field.value}") }
            }
            if (!ctaUrl.isNullOrBlank()) {
                appendLine()
                appendLine("${ctaLabel ?: VIEW_CTA_LABEL}: $ctaUrl")
            }
        }.trim()

    private fun alertEmailHeaderText(preview: WorkflowStepPreview): String {
        return listOf(alertEmailEmoji(preview), preview.title)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun alertEmailEmoji(preview: WorkflowStepPreview): String {
        val status = previewFieldValue(preview, "Status")
        if (status == "Resolved") return "✅"
        return if (preview.color == ALERT_COLOR_RED) "🔴" else "⚠️"
    }

    private fun previewFieldValue(
        preview: WorkflowStepPreview,
        label: String
    ): String =
        preview.fields
            .firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?.value
            .orEmpty()

    private fun buildAlertLifecycleHtml(preview: WorkflowStepPreview): String {
        val fieldRows = preview.fields.chunked(2).joinToString("") { row ->
            val cells =
                row.joinToString("") { field ->
                    """
                    <td style="width:50%;padding:0 20px 16px 0;vertical-align:top;">
                        <div style="font-size:14px;line-height:20px;font-weight:700;color:#334155;">
                            ${field.label.escapeHtml()}:
                        </div>
                        <div style="font-size:14px;line-height:20px;color:#475569;word-break:break-word;">
                            ${field.value.escapeHtml()}
                        </div>
                    </td>
                    """.trimIndent()
                }
            val filler =
                if (row.size == 1) {
                    """<td style="width:50%;padding:0 20px 16px 0;vertical-align:top;"></td>"""
                } else {
                    ""
                }
            "<tr>$cells$filler</tr>"
        }
        val ctaHtml =
            if (!preview.ctaUrl.isNullOrBlank() && !preview.ctaLabel.isNullOrBlank()) {
                """
                <div style="margin-top:20px;">
                    <a href="${preview.ctaUrl.escapeHtml()}" style="display:inline-block;background:#111827;
                        color:#f8fafc;padding:10px 16px;border-radius:6px;text-decoration:none;font-weight:700;
                        font-size:14px;line-height:20px;">
                        ${preview.ctaLabel.escapeHtml()}
                    </a>
                </div>
                """.trimIndent()
            } else {
                ""
            }
        val appHeader = alertEmailHeaderText(preview).escapeHtml()
        val accentColor = preview.color.escapeHtml()
        val logoUrl = moneatFaviconUrl().escapeHtml()
        return """
            <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f8fafc;
                padding:24px;">
                <div style="max-width:640px;margin:0 auto;border:1px solid #e2e8f0;border-radius:12px;
                    background:#ffffff;color:#0f172a;padding:24px;">
                    <table role="presentation" style="width:100%;border-collapse:collapse;margin:0;">
                        <tr>
                            <td style="width:48px;padding:0 12px 0 0;vertical-align:top;">
                                <div style="width:40px;height:40px;border-radius:10px;background:#ffffff;
                                    border:1px solid #e2e8f0;text-align:center;">
                                    <img src="$logoUrl" width="40" height="40" alt="Moneat" style="display:block;
                                        width:40px;height:40px;border-radius:10px;">
                                </div>
                            </td>
                            <td style="padding:0;vertical-align:top;">
                                <div style="font-size:14px;line-height:20px;font-weight:700;color:#0f172a;">Moneat</div>
                                <div style="margin-top:10px;font-size:18px;line-height:26px;font-weight:700;
                                    color:#0f172a;">
                                    $appHeader
                                </div>
                            </td>
                        </tr>
                    </table>
                    <div style="margin-top:20px;border-top:4px solid $accentColor;padding-top:18px;">
                        <div style="margin:0 0 18px;color:#334155;font-size:14px;line-height:21px;">
                            ${preview.body.toHtmlLines()}
                        </div>
                        <table role="presentation" style="width:100%;border-collapse:collapse;margin:0;">
                            $fieldRows
                        </table>
                        $ctaHtml
                        <div style="margin-top:18px;color:#64748b;font-size:12px;line-height:18px;">
                            Added by Moneat
                        </div>
                    </div>
                </div>
            </div>
        """.trimIndent()
    }

    private fun moneatFaviconUrl(): String {
        val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io").trimEnd('/')
        return "$frontendUrl/favicon.svg"
    }
}

internal fun interpolate(
    template: String,
    scope: Map<String, String>
): String =
    scope.entries.fold(template) { text, (reference, value) ->
        text.replace("{{$reference}}", value)
    }

internal fun WorkflowStepConfig.usesAlertLifecycleFormat(): Boolean =
    params[FORMAT_PARAM] == ALERT_LIFECYCLE_FORMAT

private fun humanizeEnum(value: String?): String =
    value
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.split("_")
        ?.joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        .orEmpty()

internal fun String.preformattedHtml(): String =
    "<pre style=\"font-family:system-ui,sans-serif;white-space:pre-wrap\">" +
        escapeHtml() +
        "</pre>"

private fun String.toHtmlLines(): String =
    escapeHtml().replace("\n", "<br>")

internal fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
