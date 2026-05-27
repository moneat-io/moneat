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

import com.moneat.config.EnvConfig
import com.moneat.config.RedisConfig
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertSeverity
import com.moneat.alerts.models.AlertStatus
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowPreviewField
import com.moneat.workflows.models.WorkflowPreviewRequest
import com.moneat.workflows.models.WorkflowPreviewResponse
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunQueuedMessage
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowStepPreview
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowTestMessageResponse
import com.moneat.workflows.models.WorkflowTestMessageResult
import com.moneat.workflows.models.WorkflowTriggerEvent
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.Workflows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private const val WORKFLOW_QUEUE_KEY = "moneat:workflows:queue"
private const val WORKFLOW_DLQ_KEY = "moneat:workflows:dlq"
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val SEVERITY_CRITICAL_RANK = 4
private const val SEVERITY_HIGH_RANK = 3
private const val SEVERITY_MEDIUM_RANK = 2
private const val SEVERITY_LOW_RANK = 1
private const val SEVERITY_UNKNOWN_RANK = 0
private const val ALERT_TITLE_REFERENCE = "alert.title"
private const val ALERT_DESCRIPTION_REFERENCE = "alert.description"
private const val ALERT_SEVERITY_REFERENCE = "alert.severity"
private const val ALERT_PRIORITY_REFERENCE = "alert.priority"
private const val ALERT_STATUS_REFERENCE = "alert.status"
private const val ALERT_SOURCE_REFERENCE = "alert.source"
private const val ALERT_DEDUPLICATION_KEY_REFERENCE = "alert.deduplication_key"
private const val ALERT_URL_REFERENCE = "alert.url"
private const val ALERT_CHANNEL_EMAIL_REFERENCE = "alert.channels.email"
private const val ALERT_CHANNEL_SLACK_REFERENCE = "alert.channels.slack"
private const val ALERT_CHANNEL_DISCORD_REFERENCE = "alert.channels.discord"
private const val ORGANIZATION_ID_REFERENCE = "organization.id"
private const val EMAIL_ORG_STEP = "notification.email_org"
private const val SLACK_STEP = "notification.slack"
private const val DISCORD_STEP = "notification.discord"
private const val EMAIL_CHANNEL = "email"
private const val SLACK_CHANNEL = "slack"
private const val DISCORD_CHANNEL = "discord"
private const val SKIP_IF_UNCONFIGURED_PARAM = "skip_if_unconfigured"
private const val FORMAT_PARAM = "format"
private const val ALERT_LIFECYCLE_FORMAT = "alert_lifecycle"
private const val ALERT_SOURCE_TEMPLATE_LINE = "Source: {{alert.source}}\n"
private const val ALERT_URL_TEMPLATE = "{{alert.url}}"
private const val DEFAULT_WORKFLOW_READ_ONLY_MESSAGE = "Default workflows cannot be modified"
private const val ALERT_DISPLAY_TITLE_REFERENCE = "alert.display_title"
private const val ALERT_DASHBOARD_TITLE_REFERENCE = "alert.dashboard.title"
private const val ALERT_WIDGET_TITLE_REFERENCE = "alert.widget.title"
private const val ALERT_CONDITION_REFERENCE = "alert.condition"
private const val ALERT_THRESHOLD_REFERENCE = "alert.threshold"
private const val ALERT_CURRENT_VALUE_REFERENCE = "alert.current_value"
private const val ALERT_COLOR_RED = "#E01E5A"
private const val ALERT_COLOR_GREEN = "#2EB67D"
private const val ALERT_COLOR_YELLOW = "#ECB22E"
private const val ALERT_COLOR_PURPLE = "#6366F1"
private const val VIEW_CTA_LABEL = "View"
private val ALERT_CHANNEL_REFERENCES =
    setOf(ALERT_CHANNEL_EMAIL_REFERENCE, ALERT_CHANNEL_SLACK_REFERENCE, ALERT_CHANNEL_DISCORD_REFERENCE)
private val ALERT_METADATA_REFERENCES =
    ALERT_CHANNEL_REFERENCES + setOf(
        ALERT_PRIORITY_REFERENCE,
        ALERT_DISPLAY_TITLE_REFERENCE,
        ALERT_DASHBOARD_TITLE_REFERENCE,
        ALERT_WIDGET_TITLE_REFERENCE,
        ALERT_CONDITION_REFERENCE,
        ALERT_THRESHOLD_REFERENCE,
        ALERT_CURRENT_VALUE_REFERENCE
    )

class WorkflowService(
    private val emailService: EmailService = EmailService(),
    private val slackService: SlackService = SlackService(),
    private val discordService: DiscordService = DiscordService(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun catalog() = WorkflowCatalog.response()

    fun previewWorkflow(request: WorkflowPreviewRequest): WorkflowPreviewResponse {
        val scope = previewScopeForRequest(request)
        return WorkflowPreviewResponse(
            scope = scope,
            previews = request.steps.map { step -> renderStepPreview(step, scope) }
        )
    }

    suspend fun testWorkflowMessage(
        organizationId: Int,
        request: WorkflowPreviewRequest
    ): WorkflowTestMessageResponse {
        val scope = previewScopeForRequest(request)
        return WorkflowTestMessageResponse(
            scope = scope,
            results = request.steps.map { step -> sendTestMessageStep(organizationId, step, scope) }
        )
    }

    private fun previewScopeForRequest(request: WorkflowPreviewRequest): Map<String, String> {
        val trigger = WorkflowCatalog.trigger(request.triggerName)
            ?: throw IllegalArgumentException("Unknown workflow trigger ${request.triggerName}")
        request.steps.forEach { step ->
            WorkflowCatalog.step(step.name) ?: throw IllegalArgumentException("Unknown workflow step ${step.name}")
        }
        return sampleScopeForTrigger(trigger.name) + request.scope
    }

    fun ensureDefaultWorkflowsForOrganization(organizationId: Int) {
        val now = Clock.System.now()
        transaction {
            DEFAULT_WORKFLOWS.forEach { definition ->
                val existing =
                    Workflows
                        .selectAll()
                        .where {
                            (Workflows.organizationId eq organizationId) and
                                (Workflows.systemKey eq definition.systemKey)
                        }.firstOrNull()

                if (existing != null) return@forEach

                val workflowId =
                    Workflows.insertAndGetId {
                        it[Workflows.organizationId] = organizationId
                        it[name] = definition.name
                        it[triggerName] = definition.triggerName
                        it[enabled] = true
                        it[systemKey] = definition.systemKey
                        it[createdAt] = now
                        it[updatedAt] = now
                    }.value

                insertVersion(
                    workflowId = workflowId,
                    version = 1,
                    conditions = emptyList(),
                    steps = definition.steps,
                    onceForTemplate = definition.onceForTemplate,
                    now = now
                )
            }
        }
    }

    fun ensureDefaultWorkflowsForAllOrganizations() {
        val organizationIds =
            transaction {
                Organizations.selectAll().map { it[Organizations.id] }
            }
        organizationIds.forEach { organizationId ->
            ensureDefaultWorkflowsForOrganization(organizationId)
        }
    }

    fun listWorkflows(organizationId: Int): List<WorkflowResponse> =
        transaction {
            Workflows
                .selectAll()
                .where { Workflows.organizationId eq organizationId }
                .orderBy(Workflows.updatedAt to SortOrder.DESC)
                .mapNotNull { workflowRow ->
                    val workflowId = workflowRow[Workflows.id].value
                    val version = latestVersion(workflowId) ?: return@mapNotNull null
                    workflowResponse(workflowRow, version)
                }
        }

    fun getWorkflow(
        organizationId: Int,
        workflowId: Int
    ): WorkflowResponse? =
        transaction {
            val workflowRow =
                Workflows
                    .selectAll()
                    .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                    .firstOrNull() ?: return@transaction null
            val version = latestVersion(workflowId) ?: return@transaction null
            workflowResponse(workflowRow, version)
        }

    fun createWorkflow(
        organizationId: Int,
        request: CreateWorkflowRequest
    ): WorkflowResponse {
        validateCreateRequest(request)
        val now = Clock.System.now()
        val workflowId =
            transaction {
                val id =
                    Workflows.insertAndGetId {
                        it[Workflows.organizationId] = organizationId
                        it[name] = request.name.trim()
                        it[triggerName] = request.triggerName
                        it[enabled] = request.enabled
                        it[createdAt] = now
                        it[updatedAt] = now
                    }.value
                insertVersion(
                    workflowId = id,
                    version = 1,
                    conditions = request.conditions,
                    steps = request.steps,
                    onceForTemplate = request.onceForTemplate,
                    now = now
                )
                id
            }
        return checkNotNull(getWorkflow(organizationId, workflowId))
    }

    fun updateWorkflow(
        organizationId: Int,
        workflowId: Int,
        request: UpdateWorkflowRequest
    ): WorkflowResponse? {
        val trimmedName = request.name?.trim()
        require(request.name == null || !trimmedName.isNullOrBlank()) {
            "Workflow name is required"
        }
        val now = Clock.System.now()
        transaction {
            val workflowRow =
                Workflows
                    .selectAll()
                    .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                    .firstOrNull() ?: return@transaction
            require(workflowRow[Workflows.systemKey] == null) { DEFAULT_WORKFLOW_READ_ONLY_MESSAGE }
            val currentVersion = latestVersion(workflowId) ?: return@transaction
            val triggerName = workflowRow[Workflows.triggerName]
            val conditions = request.conditions ?: currentVersion.conditions
            val steps = request.steps ?: currentVersion.steps
            val onceForTemplate = request.onceForTemplate ?: currentVersion.onceForTemplate

            validateWorkflowConfig(triggerName, conditions, steps, onceForTemplate)
            Workflows.update({ (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }) {
                trimmedName?.let { value -> it[name] = value }
                request.enabled?.let { value -> it[enabled] = value }
                it[updatedAt] = now
            }

            val configChanged = request.conditions != null || request.steps != null || request.onceForTemplate != null
            if (configChanged) {
                WorkflowVersions.update(
                    { (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.mostRecent eq true) }
                ) {
                    it[mostRecent] = false
                }
                insertVersion(
                    workflowId = workflowId,
                    version = currentVersion.version + 1,
                    conditions = conditions,
                    steps = steps,
                    onceForTemplate = onceForTemplate,
                    now = now
                )
            }
        }
        return getWorkflow(organizationId, workflowId)
    }

    fun deleteWorkflow(
        organizationId: Int,
        workflowId: Int
    ): Boolean {
        val shouldDelete =
            transaction {
                val workflowRow =
                    Workflows
                        .selectAll()
                        .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                        .firstOrNull() ?: return@transaction false
                require(workflowRow[Workflows.systemKey] == null) { DEFAULT_WORKFLOW_READ_ONLY_MESSAGE }
                true
            }
        if (!shouldDelete) return false
        return transaction {
            Workflows.deleteWhere {
                (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId)
            } > 0
        }
    }

    fun listRuns(
        organizationId: Int,
        workflowId: Int,
        limit: Int = 50
    ): List<WorkflowRunResponse> =
        transaction {
            val hasWorkflow =
                Workflows
                    .selectAll()
                    .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                    .count() > 0
            if (!hasWorkflow) return@transaction emptyList()

            WorkflowRuns
                .selectAll()
                .where {
                    (WorkflowRuns.workflowId eq workflowId) and (WorkflowRuns.organizationId eq organizationId)
                }.orderBy(WorkflowRuns.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { runResponse(it) }
        }

    suspend fun publishAlertTriggered(event: AlertLifecycleEvent) {
        val triggerName =
            if (event.status == AlertStatus.RESOLVED) {
                ALERT_RESOLVED_TRIGGER
            } else {
                ALERT_TRIGGERED_TRIGGER
            }
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = triggerName,
                organizationId = event.organizationId,
                scope = alertScope(event)
            )
        )
    }

    suspend fun publishAlertResolved(
        organizationId: Int,
        source: String,
        deduplicationKey: String,
        title: String = "Alert resolved",
        description: String = "Moneat resolved alert $deduplicationKey",
        moneatUrl: String = "",
        severity: AlertSeverity? = null
    ) {
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = ALERT_RESOLVED_TRIGGER,
                organizationId = organizationId,
                scope = mapOf(
                    ALERT_TITLE_REFERENCE to title,
                    ALERT_DESCRIPTION_REFERENCE to description,
                    ALERT_SEVERITY_REFERENCE to severity?.name.orEmpty(),
                    ALERT_PRIORITY_REFERENCE to priorityLabelForSeverity(severity?.name),
                    ALERT_STATUS_REFERENCE to AlertStatus.RESOLVED.name,
                    ALERT_SOURCE_REFERENCE to source,
                    ALERT_DEDUPLICATION_KEY_REFERENCE to deduplicationKey,
                    ALERT_URL_REFERENCE to moneatUrl,
                    ORGANIZATION_ID_REFERENCE to organizationId.toString()
                )
            )
        )
    }

    suspend fun publishTrigger(event: WorkflowTriggerEvent) {
        val trigger = WorkflowCatalog.trigger(event.triggerName) ?: return
        val candidates =
            transaction {
                Workflows
                    .selectAll()
                    .where {
                        (Workflows.organizationId eq event.organizationId) and
                            (Workflows.triggerName eq event.triggerName) and
                            (Workflows.enabled eq true)
                    }.mapNotNull { workflowRow ->
                        latestVersion(workflowRow[Workflows.id].value)?.let { version ->
                            WorkflowCandidate(workflowRow[Workflows.id].value, version)
                        }
                    }
            }

        candidates.forEach { candidate ->
            if (!conditionsMatch(event.triggerName, candidate.version.conditions, event.scope)) return@forEach
            val onceForTemplate =
                candidate.version.onceForTemplate.ifEmpty { trigger.defaultOnceForTemplate }
            val onceFor = buildOnceFor(onceForTemplate, event.scope)
            val runId = createRun(event, candidate, onceFor)
            if (runId != null) enqueueRun(runId)
        }
    }

    suspend fun executeRun(runId: Int) {
        val loaded = loadExecutableRun(runId) ?: return
        val initialProgress = loaded.progress.ifEmpty {
            loaded.version.steps.map { WorkflowRunStepProgress(step = it.name, status = "pending") }
        }
        var progress = initialProgress
        updateRunProgress(runId, "running", progress)

        val stepResults =
            coroutineScope {
                loaded.version.steps.mapIndexed { index, step ->
                    async(Dispatchers.IO) {
                        val existing = progress.getOrNull(index)
                        if (existing?.status == "complete") return@async index to existing

                        val result =
                            suspendRunCatching {
                                executeStep(
                                    organizationId = loaded.organizationId,
                                    step = step,
                                    scope = loaded.scope
                                )
                            }
                        val now = Clock.System.now().toString()
                        index to result.fold(
                            onSuccess = {
                                WorkflowRunStepProgress(step = step.name, status = "complete", completedAt = now)
                            },
                            onFailure = { error ->
                                WorkflowRunStepProgress(
                                    step = step.name,
                                    status = "failed",
                                    completedAt = now,
                                    errorMessage = error.message ?: "Unknown workflow step error"
                                )
                            }
                        )
                    }
                }
            }.awaitAll()

        progress = progress.updateFrom(stepResults)
        val failedStep = progress.firstOrNull { it.status == "failed" }
        if (failedStep != null) {
            markRunFailed(runId, progress, failedStep.errorMessage ?: "Unknown workflow step error")
            return
        }

        markRunComplete(runId, progress)
    }

    private fun createRun(
        event: WorkflowTriggerEvent,
        candidate: WorkflowCandidate,
        onceFor: String
    ): Int? =
        transaction {
            if (workflowRunExists(candidate.workflowId, onceFor)) return@transaction null
            try {
                WorkflowRuns.insertAndGetId {
                    it[workflowId] = candidate.workflowId
                    it[workflowVersionId] = candidate.version.id
                    it[organizationId] = event.organizationId
                    it[triggerName] = event.triggerName
                    it[WorkflowRuns.onceFor] = onceFor
                    it[scope] = json.encodeToString(event.scope)
                    it[status] = "pending"
                    it[progress] = "[]"
                    it[createdAt] = Clock.System.now()
                }.value
            } catch (e: ExposedSQLException) {
                if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                    logger.debug { "Workflow ${candidate.workflowId} already ran for once_for=$onceFor" }
                    null
                } else {
                    throw e
                }
            }
        }

    private fun workflowRunExists(
        workflowId: Int,
        onceFor: String
    ): Boolean =
        WorkflowRuns
            .selectAll()
            .where {
                (WorkflowRuns.workflowId eq workflowId) and
                    (WorkflowRuns.onceFor eq onceFor)
            }.count() > 0

    private suspend fun enqueueRun(runId: Int) {
        suspendRunCatching {
            RedisConfig.sync().lpush(WORKFLOW_QUEUE_KEY, json.encodeToString(WorkflowRunQueuedMessage(runId)))
        }.getOrElse { e ->
            markRunFailed(runId, emptyList(), "Failed to enqueue workflow run: ${e.message ?: "unknown error"}")
            logger.error(e) { "Failed to enqueue workflow run $runId" }
        }
    }

    private suspend fun executeStep(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ) {
        if (!notificationStepEnabled(step.name, scope)) return

        when (step.name) {
            EMAIL_ORG_STEP -> sendOrganizationEmail(organizationId, step.params, scope)
            SLACK_STEP -> {
                val skipIfUnconfigured = step.params[SKIP_IF_UNCONFIGURED_PARAM]?.toBoolean() ?: true
                val sent =
                    if (step.usesAlertLifecycleFormat()) {
                        slackService.sendWorkflowAlertMessage(
                            organizationId,
                            renderStepPreview(step, scope),
                            skipIfUnconfigured
                        )
                    } else {
                        val message = interpolate(step.params["message"].orEmpty(), scope)
                        slackService.sendWorkflowMessage(organizationId, message, skipIfUnconfigured)
                    }
                check(sent) {
                    "Slack workflow message was not sent"
                }
            }
            DISCORD_STEP -> {
                val skipIfUnconfigured = step.params[SKIP_IF_UNCONFIGURED_PARAM]?.toBoolean() ?: true
                val sent =
                    if (step.usesAlertLifecycleFormat()) {
                        discordService.sendWorkflowAlertMessage(
                            organizationId,
                            renderStepPreview(step, scope),
                            skipIfUnconfigured
                        )
                    } else {
                        val title = interpolate(step.params["title"] ?: "Moneat workflow", scope)
                        val message = interpolate(step.params["message"].orEmpty(), scope)
                        discordService.sendWorkflowMessage(organizationId, title, message, skipIfUnconfigured)
                    }
                check(sent) {
                    "Discord workflow message was not sent"
                }
            }
            else -> throw IllegalArgumentException("Unknown workflow step ${step.name}")
        }
    }

    private suspend fun sendTestMessageStep(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): WorkflowTestMessageResult {
        val channel = channelForStep(step.name)
        if (!notificationStepEnabled(step.name, scope)) {
            return WorkflowTestMessageResult(
                step = step.name,
                channel = channel,
                status = "skipped",
                errorMessage = "Notification channel is disabled for this sample"
            )
        }
        return suspendRunCatching {
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
                renderStepPreview(step, scope),
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
                renderStepPreview(step, scope),
                skipIfUnconfigured = false
            )
        } else {
            discordService.sendWorkflowMessage(
                organizationId,
                interpolate(step.params["title"] ?: "Moneat workflow", scope),
                interpolate(step.params["message"].orEmpty(), scope),
                skipIfUnconfigured = false
            )
        }

    private fun notificationStepEnabled(
        stepName: String,
        scope: Map<String, String>
    ): Boolean =
        when (stepName) {
            EMAIL_ORG_STEP -> channelEnabled(scope, ALERT_CHANNEL_EMAIL_REFERENCE)
            SLACK_STEP -> channelEnabled(scope, ALERT_CHANNEL_SLACK_REFERENCE)
            DISCORD_STEP -> channelEnabled(scope, ALERT_CHANNEL_DISCORD_REFERENCE)
            else -> true
        }

    private fun channelEnabled(
        scope: Map<String, String>,
        reference: String
    ): Boolean =
        scope[reference]?.toBooleanStrictOrNull() ?: true

    private fun sendOrganizationEmail(
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
                renderStepPreview(WorkflowStepConfig(EMAIL_ORG_STEP, params), scope)
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

    private fun renderStepPreview(
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ): WorkflowStepPreview =
        if (step.usesAlertLifecycleFormat()) {
            renderAlertLifecyclePreview(step, scope)
        } else {
            renderFreeformStepPreview(step, scope)
        }

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
                    title = "Moneat workflow",
                    body = body,
                    textBody = body,
                    color = ALERT_COLOR_PURPLE,
                    fallbackText = body
                )
            }
            DISCORD_STEP -> {
                val title = interpolate(step.params["title"] ?: "Moneat workflow", scope)
                val body = interpolate(step.params["message"].orEmpty(), scope)
                WorkflowStepPreview(
                    step = step.name,
                    channel = DISCORD_CHANNEL,
                    title = title,
                    body = body,
                    textBody = body,
                    color = ALERT_COLOR_PURPLE,
                    footer = "Moneat workflow",
                    fallbackText = "$title: $body"
                )
            }
            else -> throw IllegalArgumentException("Unknown workflow step ${step.name}")
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

    private fun channelForStep(stepName: String): String =
        when (stepName) {
            EMAIL_ORG_STEP -> EMAIL_CHANNEL
            SLACK_STEP -> SLACK_CHANNEL
            DISCORD_STEP -> DISCORD_CHANNEL
            else -> throw IllegalArgumentException("Unknown workflow step $stepName")
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
        return when (scope[ALERT_SEVERITY_REFERENCE]) {
            AlertSeverity.CRITICAL.name, AlertSeverity.HIGH.name -> ALERT_COLOR_RED
            else -> ALERT_COLOR_YELLOW
        }
    }

    private fun priorityLabel(scope: Map<String, String>): String =
        scope[ALERT_PRIORITY_REFERENCE]?.takeIf { it.isNotBlank() }
            ?: priorityLabelForSeverity(scope[ALERT_SEVERITY_REFERENCE])

    private fun priorityLabelForSeverity(severity: String?): String =
        when (severity?.uppercase()) {
            AlertSeverity.CRITICAL.name -> "[P0]"
            AlertSeverity.HIGH.name -> "[P1]"
            AlertSeverity.MEDIUM.name -> "[P2]"
            AlertSeverity.LOW.name -> "[P3]"
            else -> ""
        }

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
        if (status.equals("Resolved", ignoreCase = true)) return "✅"
        return if (preview.color.equals(ALERT_COLOR_RED, ignoreCase = true)) "🔴" else "⚠️"
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

    private fun sampleScopeForTrigger(triggerName: String): Map<String, String> {
        val resolved = triggerName == ALERT_RESOLVED_TRIGGER
        val status = if (resolved) AlertStatus.RESOLVED.name else AlertStatus.FIRING.name
        val severity = if (resolved) AlertSeverity.LOW.name else AlertSeverity.HIGH.name
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
            ALERT_SEVERITY_REFERENCE to severity,
            ALERT_PRIORITY_REFERENCE to priorityLabelForSeverity(severity),
            ALERT_STATUS_REFERENCE to status,
            ALERT_SOURCE_REFERENCE to "DASHBOARD_ALERT",
            ALERT_DEDUPLICATION_KEY_REFERENCE to "moneat-dashboard-alert-preview",
            ALERT_URL_REFERENCE to "https://moneat.io/dashboards/13",
            ALERT_DASHBOARD_TITLE_REFERENCE to "Moneat Backend System Health",
            ALERT_WIDGET_TITLE_REFERENCE to "Worker failures [1h]",
            ALERT_CONDITION_REFERENCE to ">",
            ALERT_THRESHOLD_REFERENCE to "5.00",
            ALERT_CURRENT_VALUE_REFERENCE to currentValue,
            ALERT_CHANNEL_EMAIL_REFERENCE to "true",
            ALERT_CHANNEL_SLACK_REFERENCE to "true",
            ALERT_CHANNEL_DISCORD_REFERENCE to "true",
            ORGANIZATION_ID_REFERENCE to "1"
        )
    }

    private fun latestVersion(workflowId: Int): WorkflowVersionRecord? =
        WorkflowVersions
            .selectAll()
            .where { (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.mostRecent eq true) }
            .firstOrNull()
            ?.let { versionRecord(it) }

    private fun insertVersion(
        workflowId: Int,
        version: Int,
        conditions: List<WorkflowConditionConfig>,
        steps: List<WorkflowStepConfig>,
        onceForTemplate: List<String>,
        now: kotlin.time.Instant
    ) {
        WorkflowVersions.insertAndGetId {
            it[WorkflowVersions.workflowId] = workflowId
            it[WorkflowVersions.version] = version
            it[WorkflowVersions.conditions] = json.encodeToString(conditions)
            it[WorkflowVersions.steps] = json.encodeToString(steps)
            it[WorkflowVersions.onceForTemplate] = json.encodeToString(onceForTemplate)
            it[engineConfig] = json.encodeToString(buildEngineConfig(conditions, steps, onceForTemplate))
            it[mostRecent] = true
            it[createdAt] = now
        }
    }

    private fun validateCreateRequest(request: CreateWorkflowRequest) {
        require(request.name.isNotBlank()) { "Workflow name is required" }
        validateWorkflowConfig(request.triggerName, request.conditions, request.steps, request.onceForTemplate)
    }

    private fun validateWorkflowConfig(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>,
        steps: List<WorkflowStepConfig>,
        onceForTemplate: List<String>
    ) {
        val trigger = WorkflowCatalog.trigger(triggerName)
            ?: throw IllegalArgumentException("Unknown workflow trigger $triggerName")
        val scopeReferences = trigger.scope.map { it.name }.toSet()
        conditions.forEach { condition ->
            require(condition.reference in scopeReferences) {
                "Unknown workflow condition reference ${condition.reference}"
            }
            val resourceType = checkNotNull(WorkflowCatalog.scopeType(triggerName, condition.reference))
            val resource = checkNotNull(WorkflowCatalog.resources.firstOrNull { it.type == resourceType })
            require(resource.operations.any { it.name == condition.operation }) {
                "Unsupported operation ${condition.operation} for ${condition.reference}"
            }
            if (condition.operation.requiresConditionValue()) {
                require(!condition.value.isNullOrBlank()) {
                    "Missing value for ${condition.reference} ${condition.operation}"
                }
            }
        }
        onceForTemplate.forEach { reference ->
            require(reference in scopeReferences) { "Unknown once-for reference $reference" }
        }
        steps.forEach { step ->
            val definition = WorkflowCatalog.step(step.name)
                ?: throw IllegalArgumentException("Unknown workflow step ${step.name}")
            definition.params.filter { it.required }.forEach { param ->
                require(!step.params[param.name].isNullOrBlank()) {
                    "Missing required parameter ${param.name} for ${step.name}"
                }
            }
        }
    }

    private fun conditionsMatch(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>,
        scope: Map<String, String>
    ): Boolean =
        conditions.all { condition ->
            val actual = scope[condition.reference]
            val resourceType = WorkflowCatalog.scopeType(triggerName, condition.reference)
            evaluateCondition(resourceType, actual, condition.operation, condition.value)
        }

    private fun evaluateCondition(
        resourceType: String?,
        actual: String?,
        operation: String,
        expected: String?
    ): Boolean =
        when (operation) {
            "is_set" -> !actual.isNullOrBlank()
            "is_not_set" -> actual.isNullOrBlank()
            "eq" -> equalsIgnoringCase(actual, expected)
            "neq" -> !equalsIgnoringCase(actual, expected)
            "contains" -> actual?.contains(expected.orEmpty(), ignoreCase = true) == true
            "not_contains" -> actual?.contains(expected.orEmpty(), ignoreCase = true) != true
            "gt", "gte", "lt", "lte" -> compareNumbers(actual, expected, operation)
            "at_least" -> {
                val expectedRank = severityRank(expected)
                resourceType == "AlertSeverity" && expectedRank > 0 && severityRank(actual) >= expectedRank
            }
            else -> false
        }

    private fun compareNumbers(
        actual: String?,
        expected: String?,
        operation: String
    ): Boolean {
        val actualNumber = actual?.toDoubleOrNull() ?: return false
        val expectedNumber = expected?.toDoubleOrNull() ?: return false
        return when (operation) {
            "gt" -> actualNumber > expectedNumber
            "gte" -> actualNumber >= expectedNumber
            "lt" -> actualNumber < expectedNumber
            "lte" -> actualNumber <= expectedNumber
            else -> false
        }
    }

    private fun equalsIgnoringCase(
        actual: String?,
        expected: String?
    ): Boolean =
        actual != null && actual.compareTo(expected.orEmpty(), ignoreCase = true) == 0

    private fun severityRank(value: String?): Int =
        when (value?.uppercase()) {
            "CRITICAL" -> SEVERITY_CRITICAL_RANK
            "HIGH" -> SEVERITY_HIGH_RANK
            "MEDIUM" -> SEVERITY_MEDIUM_RANK
            "LOW" -> SEVERITY_LOW_RANK
            else -> SEVERITY_UNKNOWN_RANK
        }

    private fun String.requiresConditionValue(): Boolean =
        this !in setOf("is_set", "is_not_set")

    private fun buildOnceFor(
        onceForTemplate: List<String>,
        scope: Map<String, String>
    ): String =
        onceForTemplate
            .ifEmpty { listOf(ALERT_DEDUPLICATION_KEY_REFERENCE) }
            .joinToString("|") { reference -> "$reference=${scope[reference].orEmpty()}" }

    private fun loadExecutableRun(runId: Int): ExecutableWorkflowRun? =
        transaction {
            val runRow =
                WorkflowRuns
                    .selectAll()
                    .where { WorkflowRuns.id eq runId }
                    .firstOrNull() ?: return@transaction null
            if (runRow[WorkflowRuns.status] == "complete") return@transaction null
            val version = versionById(runRow[WorkflowRuns.workflowVersionId]) ?: return@transaction null
            ExecutableWorkflowRun(
                organizationId = runRow[WorkflowRuns.organizationId],
                version = version,
                progress = decodeProgress(runRow[WorkflowRuns.progress]),
                scope = decodeScope(runRow[WorkflowRuns.scope])
            )
        }

    private fun versionById(versionId: Int): WorkflowVersionRecord? =
        WorkflowVersions
            .selectAll()
            .where { WorkflowVersions.id eq versionId }
            .firstOrNull()
            ?.let { versionRecord(it) }

    private fun updateRunProgress(
        runId: Int,
        status: String,
        progress: List<WorkflowRunStepProgress>
    ) {
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
                it[WorkflowRuns.status] = status
                it[WorkflowRuns.progress] = json.encodeToString(progress)
            }
        }
    }

    private fun markRunComplete(
        runId: Int,
        progress: List<WorkflowRunStepProgress>
    ) {
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
                it[status] = "complete"
                it[WorkflowRuns.progress] = json.encodeToString(progress)
                it[completedAt] = Clock.System.now()
                it[errorMessage] = null
            }
        }
    }

    private fun markRunFailed(
        runId: Int,
        progress: List<WorkflowRunStepProgress>,
        errorMessage: String
    ) {
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
                it[status] = "failed"
                it[WorkflowRuns.progress] = json.encodeToString(progress)
                it[WorkflowRuns.errorMessage] = errorMessage
                it[failedAt] = Clock.System.now()
            }
        }
    }

    private fun workflowResponse(
        row: ResultRow,
        version: WorkflowVersionRecord
    ): WorkflowResponse {
        val workflowId = row[Workflows.id].value
        val stats = runStats(workflowId)
        return WorkflowResponse(
            id = workflowId,
            name = row[Workflows.name],
            triggerName = row[Workflows.triggerName],
            enabled = row[Workflows.enabled],
            version = version.version,
            systemKey = row[Workflows.systemKey],
            conditions = version.conditions,
            steps = version.steps,
            onceForTemplate = version.onceForTemplate,
            createdAt = row[Workflows.createdAt].toString(),
            updatedAt = row[Workflows.updatedAt].toString(),
            lastRunAt = stats.lastRunAt,
            runCount = stats.runCount
        )
    }

    private fun runStats(workflowId: Int): WorkflowRunStats {
        val query: Query =
            WorkflowRuns
                .selectAll()
                .where { WorkflowRuns.workflowId eq workflowId }
                .orderBy(WorkflowRuns.createdAt to SortOrder.DESC)
        return WorkflowRunStats(
            lastRunAt = query.firstOrNull()?.get(WorkflowRuns.createdAt)?.toString(),
            runCount = query.count()
        )
    }

    private fun runResponse(row: ResultRow): WorkflowRunResponse =
        WorkflowRunResponse(
            id = row[WorkflowRuns.id].value,
            workflowId = row[WorkflowRuns.workflowId],
            workflowVersionId = row[WorkflowRuns.workflowVersionId],
            triggerName = row[WorkflowRuns.triggerName],
            onceFor = row[WorkflowRuns.onceFor],
            status = row[WorkflowRuns.status],
            progress = decodeProgress(row[WorkflowRuns.progress]),
            errorMessage = row[WorkflowRuns.errorMessage],
            createdAt = row[WorkflowRuns.createdAt].toString(),
            completedAt = row[WorkflowRuns.completedAt]?.toString(),
            failedAt = row[WorkflowRuns.failedAt]?.toString()
        )

    private fun versionRecord(row: ResultRow): WorkflowVersionRecord =
        WorkflowVersionRecord(
            id = row[WorkflowVersions.id].value,
            version = row[WorkflowVersions.version],
            conditions = decodeConditions(row[WorkflowVersions.conditions]),
            steps = decodeSteps(row[WorkflowVersions.steps]),
            onceForTemplate = decodeStringList(row[WorkflowVersions.onceForTemplate])
        )

    private fun decodeConditions(raw: String): List<WorkflowConditionConfig> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeSteps(raw: String): List<WorkflowStepConfig> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeStringList(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeProgress(raw: String): List<WorkflowRunStepProgress> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeScope(raw: String): Map<String, String> =
        if (raw.isBlank()) emptyMap() else json.decodeFromString(raw)

    private fun buildEngineConfig(
        conditions: List<WorkflowConditionConfig>,
        steps: List<WorkflowStepConfig>,
        onceForTemplate: List<String>
    ): List<String> =
        (conditions.map { it.reference } + onceForTemplate + steps.flatMap { it.params.values })
            .filter { it.startsWith("alert.") || it.startsWith("organization.") }
            .distinct()

    private fun alertScope(event: AlertLifecycleEvent): Map<String, String> =
        mapOf(
            ALERT_TITLE_REFERENCE to event.title,
            ALERT_DESCRIPTION_REFERENCE to event.description,
            ALERT_SEVERITY_REFERENCE to event.severity.name,
            ALERT_PRIORITY_REFERENCE to priorityLabelForSeverity(event.severity.name),
            ALERT_STATUS_REFERENCE to event.status.name,
            ALERT_SOURCE_REFERENCE to event.source.name,
            ALERT_DEDUPLICATION_KEY_REFERENCE to event.deduplicationKey,
            ALERT_URL_REFERENCE to event.moneatUrl,
            ORGANIZATION_ID_REFERENCE to event.organizationId.toString()
        ) + alertMetadataScope(event.metadata)

    private fun alertMetadataScope(metadata: Map<String, JsonElement>): Map<String, String> =
        metadata
            .mapNotNull { (reference, value) ->
                if (reference !in ALERT_METADATA_REFERENCES) return@mapNotNull null
                value.workflowScopeValue()?.let { reference to it }
            }.toMap()

    private fun JsonElement.workflowScopeValue(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.booleanOrNull?.toString() ?: primitive.contentOrNull
    }

    companion object {
        const val QUEUE_KEY = WORKFLOW_QUEUE_KEY
        const val DLQ_KEY = WORKFLOW_DLQ_KEY
        private const val ALERT_TRIGGERED_TRIGGER = "alert.triggered"
        private const val ALERT_RESOLVED_TRIGGER = "alert.resolved"
        private val DEFAULT_WORKFLOWS =
            listOf(
                DefaultWorkflowDefinition(
                    systemKey = "default_alert_notifications",
                    name = "Send alert notifications",
                    triggerName = ALERT_TRIGGERED_TRIGGER,
                    onceForTemplate = listOf(ALERT_DEDUPLICATION_KEY_REFERENCE),
                    steps = listOf(
                        WorkflowStepConfig(
                            name = EMAIL_ORG_STEP,
                            params = mapOf(
                                FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT,
                                "subject" to "[Moneat] {{alert.priority}} {{alert.display_title}}",
                                "body" to "{{alert.display_title}}\n\n{{alert.description}}\n\n" +
                                    "Priority: {{alert.priority}}\n" +
                                    ALERT_SOURCE_TEMPLATE_LINE +
                                    "Status: {{alert.status}}\n\n" +
                                    "View: {{alert.url}}"
                            )
                        ),
                        WorkflowStepConfig(
                            name = SLACK_STEP,
                            params = mapOf(
                                FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT,
                                "message" to "*{{alert.priority}} {{alert.display_title}}*\n{{alert.description}}\n\n" +
                                    "*Priority:* {{alert.priority}}\n" +
                                    "*Source:* {{alert.source}}\n" +
                                    "*Status:* {{alert.status}}\n" +
                                    ALERT_URL_TEMPLATE,
                                SKIP_IF_UNCONFIGURED_PARAM to "true"
                            )
                        ),
                        WorkflowStepConfig(
                            name = DISCORD_STEP,
                            params = mapOf(
                                FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT,
                                "title" to "{{alert.priority}} {{alert.display_title}}",
                                "message" to "{{alert.description}}\n\n" +
                                    "Priority: {{alert.priority}}\n" +
                                    ALERT_SOURCE_TEMPLATE_LINE +
                                    "Status: {{alert.status}}\n" +
                                    ALERT_URL_TEMPLATE,
                                SKIP_IF_UNCONFIGURED_PARAM to "true"
                            )
                        )
                    )
                ),
                DefaultWorkflowDefinition(
                    systemKey = "default_recovery_notifications",
                    name = "Send recovery notifications",
                    triggerName = ALERT_RESOLVED_TRIGGER,
                    onceForTemplate = listOf(ALERT_DEDUPLICATION_KEY_REFERENCE, ALERT_STATUS_REFERENCE),
                    steps = listOf(
                        WorkflowStepConfig(
                            name = EMAIL_ORG_STEP,
                            params = mapOf(
                                FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT,
                                "subject" to "[Moneat] {{alert.priority}} Resolved: {{alert.display_title}}",
                                "body" to "{{alert.display_title}}\n\n{{alert.description}}\n\n" +
                                    "Priority: {{alert.priority}}\n" +
                                    ALERT_SOURCE_TEMPLATE_LINE +
                                    "Status: {{alert.status}}\n\n" +
                                    "View: {{alert.url}}"
                            )
                        ),
                        WorkflowStepConfig(
                            name = SLACK_STEP,
                            params = mapOf(
                                FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT,
                                "message" to "*{{alert.priority}} Resolved: {{alert.display_title}}*\n" +
                                    "{{alert.description}}\n\n" +
                                    "*Priority:* {{alert.priority}}\n" +
                                    "*Source:* {{alert.source}}\n" +
                                    "*Status:* {{alert.status}}\n" +
                                    ALERT_URL_TEMPLATE,
                                SKIP_IF_UNCONFIGURED_PARAM to "true"
                            )
                        ),
                        WorkflowStepConfig(
                            name = DISCORD_STEP,
                            params = mapOf(
                                FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT,
                                "title" to "{{alert.priority}} Resolved: {{alert.display_title}}",
                                "message" to "{{alert.description}}\n\n" +
                                    "Priority: {{alert.priority}}\n" +
                                    ALERT_SOURCE_TEMPLATE_LINE +
                                    "Status: {{alert.status}}\n" +
                                    ALERT_URL_TEMPLATE,
                                SKIP_IF_UNCONFIGURED_PARAM to "true"
                            )
                        )
                    )
                )
            )
    }
}

private data class DefaultWorkflowDefinition(
    val systemKey: String,
    val name: String,
    val triggerName: String,
    val steps: List<WorkflowStepConfig>,
    val onceForTemplate: List<String>
)

private data class WorkflowVersionRecord(
    val id: Int,
    val version: Int,
    val conditions: List<WorkflowConditionConfig>,
    val steps: List<WorkflowStepConfig>,
    val onceForTemplate: List<String>
)

private data class WorkflowCandidate(
    val workflowId: Int,
    val version: WorkflowVersionRecord
)

private data class WorkflowRunStats(
    val lastRunAt: String?,
    val runCount: Long
)

private data class ExecutableWorkflowRun(
    val organizationId: Int,
    val version: WorkflowVersionRecord,
    val progress: List<WorkflowRunStepProgress>,
    val scope: Map<String, String>
)

private fun List<WorkflowRunStepProgress>.updateFrom(
    replacements: List<Pair<Int, WorkflowRunStepProgress>>
): List<WorkflowRunStepProgress> {
    val byIndex = replacements.toMap()
    return mapIndexed { index, item -> byIndex[index] ?: item }
}

private fun interpolate(
    template: String,
    scope: Map<String, String>
): String =
    scope.entries.fold(template) { text, (reference, value) ->
        text.replace("{{$reference}}", value)
    }

private fun WorkflowStepConfig.usesAlertLifecycleFormat(): Boolean =
    params[FORMAT_PARAM] == ALERT_LIFECYCLE_FORMAT

private fun humanizeEnum(value: String?): String =
    value
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.split("_")
        ?.joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        .orEmpty()

private fun String.preformattedHtml(): String =
    "<pre style=\"font-family:system-ui,sans-serif;white-space:pre-wrap\">" +
        escapeHtml() +
        "</pre>"

private fun String.toHtmlLines(): String =
    escapeHtml().replace("\n", "<br>")

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
