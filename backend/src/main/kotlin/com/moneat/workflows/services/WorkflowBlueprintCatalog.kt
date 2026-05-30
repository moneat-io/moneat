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

import com.moneat.workflows.engine.temporal.LinearGraphAdapter
import com.moneat.workflows.models.WorkflowBlueprintDetail
import com.moneat.workflows.models.WorkflowBlueprintSummary
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowStepConfig

/**
 * Curated, ready-to-edit workflow blueprints built exclusively from the workflow
 * catalog vocabulary. Each blueprint derives a valid graph through
 * [LinearGraphAdapter.graphFromLegacy], so instantiating one always yields a
 * workflow that passes graph validation. Blueprints instantiate as disabled
 * drafts that administrators can review, edit, and publish.
 */
object WorkflowBlueprintCatalog {
    fun list(): List<WorkflowBlueprintSummary> =
        BLUEPRINTS.map { blueprint ->
            WorkflowBlueprintSummary(
                key = blueprint.key,
                name = blueprint.name,
                description = blueprint.description,
                category = blueprint.category,
                triggerName = blueprint.triggerName,
                tags = blueprint.tags
            )
        }

    fun get(key: String): WorkflowBlueprint? = BLUEPRINTS.firstOrNull { it.key == key }

    fun detail(blueprint: WorkflowBlueprint): WorkflowBlueprintDetail =
        WorkflowBlueprintDetail(
            key = blueprint.key,
            name = blueprint.name,
            description = blueprint.description,
            category = blueprint.category,
            triggerName = blueprint.triggerName,
            tags = blueprint.tags,
            conditions = blueprint.conditions,
            steps = blueprint.steps,
            graph = blueprint.graph(),
            onceForTemplate = blueprint.onceForTemplate
        )

    private const val CATEGORY_ALERTING = "alerting"
    private const val CATEGORY_INCIDENT = "incident"
    private const val CATEGORY_SECURITY = "security"
    private const val CATEGORY_UPTIME = "uptime"
    private const val CATEGORY_TRIAGE = "triage"

    private const val DEDUP_KEY = "alert.deduplication_key"
    private const val ALERT_STATUS = "alert.status"
    private const val INCIDENT_STATUS = "incident.status"
    private const val SECURITY_RULE_ID = "security.rule_id"
    private const val SECURITY_RESOURCE = "security.resource"

    private val BLUEPRINTS: List<WorkflowBlueprint> =
        listOf(
            WorkflowBlueprint(
                key = "alert_notify_slack",
                name = "Notify Slack on alert",
                description = "Post a formatted message to the Slack alert channel when an alert fires.",
                category = CATEGORY_ALERTING,
                triggerName = "alert.triggered",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("slack", "notifications"),
                steps = listOf(
                    slackAlertStep(
                        "*{{alert.priority}} {{alert.display_title}}*\n{{alert.description}}\n{{alert.url}}"
                    )
                )
            ),
            WorkflowBlueprint(
                key = "alert_email_critical",
                name = "Email team on critical alert",
                description = "Email organization members when a critical-severity alert fires.",
                category = CATEGORY_ALERTING,
                triggerName = "alert.triggered",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("email", "critical"),
                conditions = listOf(
                    WorkflowConditionConfig("alert.severity", "at_least", "HIGH")
                ),
                steps = listOf(
                    emailAlertStep(
                        subject = "[Moneat] {{alert.priority}} {{alert.display_title}}",
                        body = "{{alert.description}}\n\nStatus: {{alert.status}}\nView: {{alert.url}}"
                    )
                )
            ),
            WorkflowBlueprint(
                key = "alert_recovery_slack",
                name = "Announce recovery to Slack",
                description = "Post a recovery message to Slack when an alert resolves.",
                category = CATEGORY_ALERTING,
                triggerName = "alert.resolved",
                onceForTemplate = listOf(DEDUP_KEY, ALERT_STATUS),
                tags = listOf("slack", "recovery"),
                steps = listOf(
                    slackAlertStep("*Resolved:* {{alert.display_title}}\n{{alert.url}}")
                )
            ),
            WorkflowBlueprint(
                key = "alert_dual_channel",
                name = "Notify Slack and Discord on alert",
                description = "Send alerts to both Slack and Discord alert channels in parallel.",
                category = CATEGORY_ALERTING,
                triggerName = "alert.triggered",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("slack", "discord"),
                steps = listOf(
                    slackAlertStep("*{{alert.priority}} {{alert.display_title}}*\n{{alert.description}}"),
                    discordAlertStep(
                        title = "{{alert.priority}} {{alert.display_title}}",
                        message = "{{alert.description}}\nStatus: {{alert.status}}"
                    )
                )
            ),
            WorkflowBlueprint(
                key = "monitor_page_oncall",
                name = "Page on-call when a monitor alerts",
                description = "Page the on-call responders and notify Slack when a monitor fires.",
                category = CATEGORY_ALERTING,
                triggerName = "monitor.alerted",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("oncall", "monitor"),
                steps = listOf(
                    oncallPageStep(
                        title = "{{alert.display_title}}",
                        description = "{{alert.description}}"
                    ),
                    slackAlertStep("Paged on-call for *{{alert.display_title}}*")
                )
            ),
            WorkflowBlueprint(
                key = "uptime_down_status_incident",
                name = "Open status incident on outage",
                description = "Create a status page incident and notify Slack when an uptime monitor is down.",
                category = CATEGORY_UPTIME,
                triggerName = "uptime.down",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("uptime", "statuspage"),
                steps = listOf(
                    statusIncidentStep(
                        title = "Investigating: {{alert.display_title}}",
                        message = "We are investigating an outage detected by uptime monitoring."
                    ),
                    slackAlertStep("Opened a status incident for *{{alert.display_title}}*")
                )
            ),
            WorkflowBlueprint(
                key = "uptime_up_status_update",
                name = "Update status page on recovery",
                description = "Update the status page and notify Slack when an uptime monitor recovers.",
                category = CATEGORY_UPTIME,
                triggerName = "uptime.up",
                onceForTemplate = listOf(DEDUP_KEY, ALERT_STATUS),
                tags = listOf("uptime", "statuspage", "recovery"),
                steps = listOf(
                    statusUpdateStep(description = "Service has recovered and is operating normally."),
                    slackAlertStep("Service recovered: *{{alert.display_title}}*")
                )
            ),
            WorkflowBlueprint(
                key = "synthetic_failed_notify",
                name = "Notify on synthetic test failure",
                description = "Email members and post to Slack when a synthetic test fails.",
                category = CATEGORY_ALERTING,
                triggerName = "synthetic.failed",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("synthetic", "notifications"),
                steps = listOf(
                    emailAlertStep(
                        subject = "[Moneat] Synthetic failure: {{alert.display_title}}",
                        body = "{{alert.description}}\nView: {{alert.url}}"
                    ),
                    slackAlertStep("Synthetic test failed: *{{alert.display_title}}*")
                )
            ),
            WorkflowBlueprint(
                key = "incident_created_page",
                name = "Page on-call when an incident opens",
                description = "Page responders and announce a newly created incident on Slack.",
                category = CATEGORY_INCIDENT,
                triggerName = "incident.created",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("incident", "oncall"),
                steps = listOf(
                    oncallPageStep(
                        title = "{{incident.title}}",
                        description = "Incident {{incident.id}} created with severity {{incident.severity}}."
                    ),
                    slackAlertStep("*Incident opened:* {{incident.title}} ({{incident.severity}})")
                )
            ),
            WorkflowBlueprint(
                key = "incident_created_status",
                name = "Announce incident on status page",
                description = "Create a status page incident when a new incident is created.",
                category = CATEGORY_INCIDENT,
                triggerName = "incident.created",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("incident", "statuspage"),
                steps = listOf(
                    statusIncidentStep(
                        title = "{{incident.title}}",
                        message = "We are aware of an incident and are actively investigating."
                    )
                )
            ),
            WorkflowBlueprint(
                key = "incident_resolved_notify",
                name = "Announce incident resolution",
                description = "Update the status page and notify Slack when an incident resolves.",
                category = CATEGORY_INCIDENT,
                triggerName = "incident.resolved",
                onceForTemplate = listOf(DEDUP_KEY, INCIDENT_STATUS),
                tags = listOf("incident", "recovery"),
                steps = listOf(
                    statusUpdateStep(description = "The incident has been resolved."),
                    slackAlertStep("*Incident resolved:* {{incident.title}}")
                )
            ),
            WorkflowBlueprint(
                key = "security_high_notify",
                name = "Notify on high-severity security signal",
                description = "Email members and post to Slack on a high-or-greater security signal.",
                category = CATEGORY_SECURITY,
                triggerName = "security.signal",
                onceForTemplate = listOf(SECURITY_RULE_ID, SECURITY_RESOURCE),
                tags = listOf("security", "notifications"),
                conditions = listOf(
                    WorkflowConditionConfig("security.severity", "at_least", "high")
                ),
                steps = listOf(
                    emailAlertStep(
                        subject = "[Moneat] Security signal: {{security.rule_name}}",
                        body = "Rule {{security.rule_id}} on {{security.resource}}."
                    ),
                    slackAlertStep("Security signal *{{security.rule_name}}* on {{security.resource}}")
                )
            ),
            WorkflowBlueprint(
                key = "security_page_oncall",
                name = "Page on-call for security signal",
                description = "Page responders when a high-or-greater security signal arrives.",
                category = CATEGORY_SECURITY,
                triggerName = "security.signal",
                onceForTemplate = listOf(SECURITY_RULE_ID, SECURITY_RESOURCE),
                tags = listOf("security", "oncall"),
                conditions = listOf(
                    WorkflowConditionConfig("security.severity", "at_least", "high")
                ),
                steps = listOf(
                    oncallPageStep(
                        title = "Security: {{security.rule_name}}",
                        description = "Signal {{security.rule_id}} on {{security.resource}}."
                    )
                )
            ),
            WorkflowBlueprint(
                key = "monitor_triage_logs",
                name = "Search logs when a monitor alerts",
                description = "Pull recent logs for enrichment and post a triage note to Slack.",
                category = CATEGORY_TRIAGE,
                triggerName = "monitor.alerted",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("triage", "logs"),
                steps = listOf(
                    WorkflowStepConfig(
                        name = "moneat.logs.search",
                        params = mapOf(
                            "query" to "{{alert.display_title}}",
                            "levels" to "ERROR,WARN",
                            "limit" to "50"
                        )
                    ),
                    slackAlertStep("Collected recent logs for *{{alert.display_title}}* triage.")
                )
            ),
            WorkflowBlueprint(
                key = "incident_silence_and_notify",
                name = "Silence alerts during an incident",
                description = "Silence noisy alerts and notify Slack when an incident is created.",
                category = CATEGORY_INCIDENT,
                triggerName = "incident.created",
                onceForTemplate = listOf(DEDUP_KEY),
                tags = listOf("incident", "silence"),
                steps = listOf(
                    WorkflowStepConfig(
                        name = "alert.silence",
                        params = mapOf("reason" to "Active incident {{incident.id}}")
                    ),
                    slackAlertStep("Silenced alerts for active incident *{{incident.title}}*")
                )
            )
        )

    private fun slackAlertStep(message: String): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "notification.slack",
            params = mapOf("message" to message, "skip_if_unconfigured" to "true")
        )

    private fun discordAlertStep(
        title: String,
        message: String
    ): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "notification.discord",
            params = mapOf("title" to title, "message" to message, "skip_if_unconfigured" to "true")
        )

    private fun emailAlertStep(
        subject: String,
        body: String
    ): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "notification.email_org",
            params = mapOf("subject" to subject, "body" to body)
        )

    private fun oncallPageStep(
        title: String,
        description: String
    ): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "oncall.page",
            params = mapOf(
                "escalation_policy_id" to "{{escalation_policy_id}}",
                "title" to title,
                "description" to description
            )
        )

    private fun statusIncidentStep(
        title: String,
        message: String
    ): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "statuspage.incident.create",
            params = mapOf(
                "status_page_id" to "{{status_page_id}}",
                "title" to title,
                "message" to message
            )
        )

    private fun statusUpdateStep(description: String): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "statuspage.update",
            params = mapOf(
                "status_page_id" to "{{status_page_id}}",
                "description" to description
            )
        )
}

data class WorkflowBlueprint(
    val key: String,
    val name: String,
    val description: String,
    val category: String,
    val triggerName: String,
    val onceForTemplate: List<String>,
    val tags: List<String>,
    val conditions: List<WorkflowConditionConfig> = emptyList(),
    val steps: List<WorkflowStepConfig> = emptyList()
) {
    fun graph(): WorkflowGraphConfig =
        LinearGraphAdapter.graphFromLegacy(triggerName, conditions, steps)
}
