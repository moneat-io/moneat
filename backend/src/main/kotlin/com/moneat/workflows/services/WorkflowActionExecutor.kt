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

import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowTestMessageResult
import com.moneat.workflows.models.workflowStringView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val WORKFLOW_ACTOR_ID_SCOPE = "workflow.actor_id"
private const val WORKFLOW_CALLER_SCOPE = "workflow.caller"

class WorkflowActionExecutor(
    private val emailService: EmailService,
    private val slackService: SlackService,
    private val discordService: DiscordService,
    private val renderer: WorkflowStepRenderer,
    private val trustedActions: WorkflowTrustedActionExecutor? = null
) {
    suspend fun executeStep(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, JsonElement>,
        runId: Int? = null,
        nodeId: String? = null,
    ): Map<String, JsonElement> {
        val stringScope = scope.workflowStringView()
        if (!notificationStepEnabled(step.name, stringScope)) {
            return mapOf("skipped" to JsonPrimitive(true))
        }

        return when (step.name) {
            EMAIL_ORG_STEP -> mapOf(
                "recipient_count" to JsonPrimitive(
                    sendOrganizationEmail(
                        organizationId,
                        step.params,
                        stringScope
                    )
                )
            )
            SLACK_STEP -> executeSlackStep(organizationId, step, stringScope)
            DISCORD_STEP -> executeDiscordStep(organizationId, step, stringScope)
            else -> if (trustedActions?.supports(step.name) == true) {
                trustedActions.execute(
                    organizationId = organizationId,
                    stepName = step.name,
                    params = step.params.mapValues { (_, value) -> interpolate(value, stringScope) },
                    actorUserId = callerUserIdFromScope(organizationId, stringScope),
                    idempotencyKey = workflowStepIdempotencyKey(runId, nodeId),
                )
            } else {
                throw IllegalArgumentException("Unknown workflow step ${step.name}")
            }
        }
    }

    private fun workflowStepIdempotencyKey(
        runId: Int?,
        nodeId: String?,
    ): String? =
        if (runId == null || nodeId.isNullOrBlank()) {
            null
        } else {
            "workflow:$runId:$nodeId"
        }

    private fun callerUserIdFromScope(
        organizationId: Int,
        scope: Map<String, String>
    ): Int? =
        sequenceOf(WORKFLOW_ACTOR_ID_SCOPE, WORKFLOW_CALLER_SCOPE)
            .firstNotNullOfOrNull { key -> scope[key]?.let { resolveScopedUserId(organizationId, it) } }

    private fun resolveScopedUserId(
        organizationId: Int,
        rawUserId: String
    ): Int? {
        val normalized = rawUserId.trim()
        if (normalized.isEmpty()) return null

        val resourceId = normalized.toUuidOrNull()
        if (resourceId == null) {
            return normalized.toIntOrNull()
        }

        return transaction {
            Memberships
                .innerJoin(Users)
                .selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                        (Users.resource_id eq resourceId)
                }
                .firstOrNull()
                ?.get(Users.id)
        }
    }

    suspend fun sendTestMessageStep(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): WorkflowTestMessageResult {
        val channel = renderer.channelForStep(step.name)
        if (!notificationStepEnabled(step.name, scope)) {
            return WorkflowTestMessageResult(
                step = step.name,
                channel = channel,
                status = "skipped",
                errorMessage = "Notification channel is disabled for this sample"
            )
        }
        return com.moneat.utils.suspendRunCatching {
            sendTestMessageStepOrThrow(organizationId, step, scope)
        }.fold(
            onSuccess = { WorkflowTestMessageResult(step.name, channel, "sent") },
            onFailure = { error ->
                WorkflowTestMessageResult(
                    step = step.name,
                    channel = channel,
                    status = "failed",
                    errorMessage = error.message ?: "Test message was not sent"
                )
            }
        )
    }

    internal fun notificationStepEnabled(
        stepName: String,
        scope: Map<String, String>
    ): Boolean =
        when (stepName) {
            EMAIL_ORG_STEP -> channelEnabled(scope, ALERT_CHANNEL_EMAIL_REFERENCE)
            SLACK_STEP -> channelEnabled(scope, ALERT_CHANNEL_SLACK_REFERENCE)
            DISCORD_STEP -> channelEnabled(scope, ALERT_CHANNEL_DISCORD_REFERENCE)
            else -> true
        }

    internal fun sendOrganizationEmail(
        organizationId: Int,
        params: Map<String, String>,
        scope: Map<String, String>
    ): Int {
        val recipients =
            transaction {
                Memberships
                    .innerJoin(Users)
                    .selectAll()
                    .where {
                        (Memberships.organization_id eq organizationId) and
                            (Users.email_verified eq true)
                    }.map { row -> row[Users.email] }
            }
        val subject = interpolate(params["subject"] ?: "Moneat workflow: {{alert.title}}", scope)
        val body = interpolate(params["body"].orEmpty(), scope)
        val preview =
            if (params[FORMAT_PARAM] == ALERT_LIFECYCLE_FORMAT) {
                renderer.renderStepPreview(WorkflowStepConfig(EMAIL_ORG_STEP, params), scope)
            } else {
                null
            }
        val htmlBody = preview?.htmlBody ?: body.preformattedHtml()
        val textBody = preview?.textBody ?: body
        val emailSubject = preview?.subject ?: subject
        recipients.forEach { email ->
            emailService.sendEmail(email, emailSubject, htmlBody, textBody, "workflow")
        }
        return recipients.size
    }

    private suspend fun executeSlackStep(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): Map<String, JsonElement> {
        val skipIfUnconfigured = step.params[SKIP_IF_UNCONFIGURED_PARAM]?.toBooleanStrictOrNull() ?: true
        val sent =
            if (step.usesAlertLifecycleFormat()) {
                slackService.sendWorkflowAlertMessage(
                    organizationId,
                    renderer.renderStepPreview(step, scope),
                    skipIfUnconfigured
                )
            } else {
                val message = interpolate(step.params["message"].orEmpty(), scope)
                slackService.sendWorkflowMessage(organizationId, message, skipIfUnconfigured)
            }
        check(sent) {
            "Slack workflow message was not sent"
        }
        return mapOf("sent" to JsonPrimitive(sent))
    }

    private suspend fun executeDiscordStep(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): Map<String, JsonElement> {
        val skipIfUnconfigured = step.params[SKIP_IF_UNCONFIGURED_PARAM]?.toBooleanStrictOrNull() ?: true
        val sent =
            if (step.usesAlertLifecycleFormat()) {
                discordService.sendWorkflowAlertMessage(
                    organizationId,
                    renderer.renderStepPreview(step, scope),
                    skipIfUnconfigured
                )
            } else {
                val title = interpolate(step.params["title"] ?: DEFAULT_WORKFLOW_TITLE, scope)
                val message = interpolate(step.params["message"].orEmpty(), scope)
                discordService.sendWorkflowMessage(organizationId, title, message, skipIfUnconfigured)
            }
        check(sent) {
            "Discord workflow message was not sent"
        }
        return mapOf("sent" to JsonPrimitive(sent))
    }

    private suspend fun sendTestMessageStepOrThrow(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ) {
        when (step.name) {
            EMAIL_ORG_STEP -> {
                val recipientCount = sendOrganizationEmail(organizationId, step.params, scope)
                check(recipientCount > 0) {
                    "No verified organization email recipients"
                }
            }
            SLACK_STEP -> check(sendSlackTestMessage(organizationId, step, scope)) {
                "Slack test message was not sent"
            }
            DISCORD_STEP -> check(sendDiscordTestMessage(organizationId, step, scope)) {
                "Discord test message was not sent"
            }
            else -> throw IllegalArgumentException("Unknown workflow step ${step.name}")
        }
    }

    private suspend fun sendSlackTestMessage(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): Boolean =
        if (step.usesAlertLifecycleFormat()) {
            slackService.sendWorkflowAlertMessage(
                organizationId,
                renderer.renderStepPreview(step, scope),
                skipIfUnconfigured = false
            )
        } else {
            slackService.sendWorkflowMessage(
                organizationId,
                interpolate(step.params["message"].orEmpty(), scope),
                skipIfUnconfigured = false
            )
        }

    private suspend fun sendDiscordTestMessage(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): Boolean =
        if (step.usesAlertLifecycleFormat()) {
            discordService.sendWorkflowAlertMessage(
                organizationId,
                renderer.renderStepPreview(step, scope),
                skipIfUnconfigured = false
            )
        } else {
            discordService.sendWorkflowMessage(
                organizationId,
                interpolate(step.params["title"] ?: DEFAULT_WORKFLOW_TITLE, scope),
                interpolate(step.params["message"].orEmpty(), scope),
                skipIfUnconfigured = false
            )
        }

    private fun channelEnabled(
        scope: Map<String, String>,
        reference: String
    ): Boolean =
        scope[reference]?.toBooleanStrictOrNull() ?: true
}
