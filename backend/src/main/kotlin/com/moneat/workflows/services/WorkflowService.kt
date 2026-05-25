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

import com.moneat.config.RedisConfig
import com.moneat.incident.models.IncidentEvent
import com.moneat.incident.models.IncidentStatus
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
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunQueuedMessage
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowTriggerEvent
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.Workflows
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
private const val ALERT_STATUS_REFERENCE = "alert.status"
private const val ALERT_SOURCE_REFERENCE = "alert.source"
private const val ALERT_DEDUPLICATION_KEY_REFERENCE = "alert.deduplication_key"
private const val ALERT_URL_REFERENCE = "alert.url"
private const val ORGANIZATION_ID_REFERENCE = "organization.id"
private const val EMAIL_ORG_STEP = "notification.email_org"
private const val SLACK_STEP = "notification.slack"
private const val DISCORD_STEP = "notification.discord"
private const val SKIP_IF_UNCONFIGURED_PARAM = "skip_if_unconfigured"
private const val ALERT_SOURCE_TEMPLATE_LINE = "Source: {{alert.source}}\n"
private const val ALERT_URL_TEMPLATE = "{{alert.url}}"

class WorkflowService(
    private val emailService: EmailService = EmailService(),
    private val slackService: SlackService = SlackService(),
    private val discordService: DiscordService = DiscordService(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun catalog() = WorkflowCatalog.response()

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
        val now = Clock.System.now()
        transaction {
            val workflowRow =
                Workflows
                    .selectAll()
                    .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                    .firstOrNull() ?: return@transaction
            val currentVersion = latestVersion(workflowId) ?: return@transaction
            val triggerName = workflowRow[Workflows.triggerName]
            val conditions = request.conditions ?: currentVersion.conditions
            val steps = request.steps ?: currentVersion.steps
            val onceForTemplate = request.onceForTemplate ?: currentVersion.onceForTemplate

            validateWorkflowConfig(triggerName, conditions, steps, onceForTemplate)
            Workflows.update({ (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }) {
                request.name?.let { value -> it[name] = value.trim() }
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
    ): Boolean =
        transaction {
            Workflows.deleteWhere {
                (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId)
            } > 0
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

    suspend fun publishAlertTriggered(event: IncidentEvent) {
        val triggerName =
            if (event.status == IncidentStatus.RESOLVED) {
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
        moneatUrl: String = ""
    ) {
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = ALERT_RESOLVED_TRIGGER,
                organizationId = organizationId,
                scope = mapOf(
                    ALERT_TITLE_REFERENCE to title,
                    ALERT_DESCRIPTION_REFERENCE to description,
                    ALERT_SEVERITY_REFERENCE to "",
                    ALERT_STATUS_REFERENCE to IncidentStatus.RESOLVED.name,
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

        for ((index, step) in loaded.version.steps.withIndex()) {
            val existing = progress.getOrNull(index)
            if (existing?.status == "complete") continue

            val result =
                suspendRunCatching {
                    executeStep(
                        organizationId = loaded.organizationId,
                        step = step,
                        scope = loaded.scope
                    )
                }
            val now = Clock.System.now().toString()
            progress =
                progress.updateAt(index) {
                    result.fold(
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

            result.exceptionOrNull()?.let { error ->
                markRunFailed(runId, progress, error.message ?: "Unknown workflow step error")
                return
            }
            updateRunProgress(runId, "running", progress)
        }

        markRunComplete(runId, progress)
    }

    private fun createRun(
        event: WorkflowTriggerEvent,
        candidate: WorkflowCandidate,
        onceFor: String
    ): Int? =
        transaction {
            try {
                WorkflowRuns.insertAndGetId {
                    it[workflowId] = candidate.workflowId
                    it[workflowVersionId] = candidate.version.id
                    it[organizationId] = event.organizationId
                    it[triggerName] = event.triggerName
                    it[WorkflowRuns.onceFor] = onceFor.take(MAX_ONCE_FOR_LENGTH)
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

    private suspend fun enqueueRun(runId: Int) {
        suspendRunCatching {
            RedisConfig.sync().lpush(WORKFLOW_QUEUE_KEY, json.encodeToString(WorkflowRunQueuedMessage(runId)))
        }.getOrElse { e ->
            logger.error(e) { "Failed to enqueue workflow run $runId" }
        }
    }

    private suspend fun executeStep(
        organizationId: Int,
        step: WorkflowStepConfig,
        scope: Map<String, String>
    ) {
        when (step.name) {
            EMAIL_ORG_STEP -> sendOrganizationEmail(organizationId, step.params, scope)
            SLACK_STEP -> {
                val message = interpolate(step.params["message"].orEmpty(), scope)
                val skipIfUnconfigured = step.params[SKIP_IF_UNCONFIGURED_PARAM]?.toBoolean() ?: true
                check(slackService.sendWorkflowMessage(organizationId, message, skipIfUnconfigured)) {
                    "Slack workflow message was not sent"
                }
            }
            DISCORD_STEP -> {
                val title = interpolate(step.params["title"] ?: "Moneat workflow", scope)
                val message = interpolate(step.params["message"].orEmpty(), scope)
                val skipIfUnconfigured = step.params[SKIP_IF_UNCONFIGURED_PARAM]?.toBoolean() ?: true
                check(discordService.sendWorkflowMessage(organizationId, title, message, skipIfUnconfigured)) {
                    "Discord workflow message was not sent"
                }
            }
            else -> throw IllegalArgumentException("Unknown workflow step ${step.name}")
        }
    }

    private fun sendOrganizationEmail(
        organizationId: Int,
        params: Map<String, String>,
        scope: Map<String, String>
    ) {
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
        val htmlBody = "<pre style=\"font-family:system-ui,sans-serif;white-space:pre-wrap\">" +
            body.escapeHtml() +
            "</pre>"
        recipients.forEach { email ->
            emailService.sendEmail(email, subject, htmlBody, body, "workflow")
        }
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
                resourceType == "IncidentSeverity" && expectedRank > 0 && severityRank(actual) >= expectedRank
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

    private fun alertScope(event: IncidentEvent): Map<String, String> =
        mapOf(
            ALERT_TITLE_REFERENCE to event.title,
            ALERT_DESCRIPTION_REFERENCE to event.description,
            ALERT_SEVERITY_REFERENCE to event.severity.name,
            ALERT_STATUS_REFERENCE to event.status.name,
            ALERT_SOURCE_REFERENCE to event.source.name,
            ALERT_DEDUPLICATION_KEY_REFERENCE to event.deduplicationKey,
            ALERT_URL_REFERENCE to event.moneatUrl,
            ORGANIZATION_ID_REFERENCE to event.organizationId.toString()
        )

    companion object {
        const val QUEUE_KEY = WORKFLOW_QUEUE_KEY
        const val DLQ_KEY = WORKFLOW_DLQ_KEY
        private const val ALERT_TRIGGERED_TRIGGER = "alert.triggered"
        private const val ALERT_RESOLVED_TRIGGER = "alert.resolved"
        private const val MAX_ONCE_FOR_LENGTH = 512
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
                                "subject" to "[Moneat] {{alert.title}}",
                                "body" to "{{alert.title}}\n\n{{alert.description}}\n\n" +
                                    "Severity: {{alert.severity}}\n" +
                                    ALERT_SOURCE_TEMPLATE_LINE +
                                    "Status: {{alert.status}}\n\n" +
                                    "View in Moneat: {{alert.url}}"
                            )
                        ),
                        WorkflowStepConfig(
                            name = SLACK_STEP,
                            params = mapOf(
                                "message" to "*{{alert.title}}*\n{{alert.description}}\n\n" +
                                    "*Severity:* {{alert.severity}}\n" +
                                    "*Source:* {{alert.source}}\n" +
                                    "*Status:* {{alert.status}}\n" +
                                    ALERT_URL_TEMPLATE,
                                SKIP_IF_UNCONFIGURED_PARAM to "true"
                            )
                        ),
                        WorkflowStepConfig(
                            name = DISCORD_STEP,
                            params = mapOf(
                                "title" to "{{alert.title}}",
                                "message" to "{{alert.description}}\n\n" +
                                    "Severity: {{alert.severity}}\n" +
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
                                "subject" to "[Moneat] Resolved: {{alert.title}}",
                                "body" to "{{alert.title}}\n\n{{alert.description}}\n\n" +
                                    ALERT_SOURCE_TEMPLATE_LINE +
                                    "Status: {{alert.status}}\n\n" +
                                    "View in Moneat: {{alert.url}}"
                            )
                        ),
                        WorkflowStepConfig(
                            name = SLACK_STEP,
                            params = mapOf(
                                "message" to "*Resolved: {{alert.title}}*\n{{alert.description}}\n\n" +
                                    "*Source:* {{alert.source}}\n" +
                                    "*Status:* {{alert.status}}\n" +
                                    ALERT_URL_TEMPLATE,
                                SKIP_IF_UNCONFIGURED_PARAM to "true"
                            )
                        ),
                        WorkflowStepConfig(
                            name = DISCORD_STEP,
                            params = mapOf(
                                "title" to "Resolved: {{alert.title}}",
                                "message" to "{{alert.description}}\n\n" +
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

private fun List<WorkflowRunStepProgress>.updateAt(
    index: Int,
    replacement: (WorkflowRunStepProgress?) -> WorkflowRunStepProgress
): List<WorkflowRunStepProgress> =
    mapIndexed { itemIndex, item ->
        if (itemIndex == index) replacement(item) else item
    }

private fun interpolate(
    template: String,
    scope: Map<String, String>
): String =
    scope.entries.fold(template) { text, (reference, value) ->
        text.replace("{{$reference}}", value)
    }

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
