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

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertSeverity
import com.moneat.alerts.models.AlertStatus
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.Organizations
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.engine.WorkflowConditionEvaluator
import com.moneat.workflows.engine.temporal.ExecuteActionActivityImpl
import com.moneat.workflows.engine.temporal.LinearGraphAdapter
import com.moneat.workflows.engine.temporal.PersistRunActivityImpl
import com.moneat.workflows.engine.temporal.PersistRunFailureInput
import com.moneat.workflows.engine.temporal.TemporalClientProvider
import com.moneat.workflows.engine.temporal.TemporalWorkflowExecutionEngine
import com.moneat.workflows.engine.temporal.WorkflowDirectRunExecutor
import com.moneat.workflows.engine.temporal.WorkflowExecutionEngine
import com.moneat.workflows.engine.temporal.WorkflowStartRequest
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.ManualWorkflowRunRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowPreviewRequest
import com.moneat.workflows.models.WorkflowPreviewResponse
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.models.WorkflowRunStepResponse
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowRunSteps
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowTestMessageResponse
import com.moneat.workflows.models.WorkflowTriggerEvent
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.Workflows
import com.moneat.workflows.models.typedWorkflowScope
import com.moneat.workflows.models.workflowJson
import com.moneat.workflows.models.workflowObjectValue
import com.moneat.workflows.models.workflowStringView
import com.moneat.workflows.models.workflowStringValue
import kotlinx.serialization.encodeToString
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
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val TEMPORAL_WORKFLOW_ID_HASH_LENGTH = 32
private const val UNSIGNED_BYTE_MASK = 0xff
private const val DEFAULT_WORKFLOW_READ_ONLY_MESSAGE = "Default workflows cannot be modified"
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
    private val stepRenderer: WorkflowStepRenderer = WorkflowStepRenderer(),
    private val actionExecutor: WorkflowActionExecutor = WorkflowActionExecutor(
        emailService,
        slackService,
        discordService,
        stepRenderer
    ),
    private val runPersistence: PersistRunActivityImpl = PersistRunActivityImpl(),
    private val executionEngine: WorkflowExecutionEngine = TemporalWorkflowExecutionEngine(TemporalClientProvider())
) {
    private val json = workflowJson
    private val graphValidator = WorkflowGraphValidator()
    private val directRunExecutor =
        WorkflowDirectRunExecutor(
            ExecuteActionActivityImpl(actionExecutor),
            runPersistence
        )

    fun catalog() = WorkflowCatalog.response()

    fun previewWorkflow(request: WorkflowPreviewRequest): WorkflowPreviewResponse {
        val scope = previewScopeForRequest(request)
        val graph = request.graph ?: LinearGraphAdapter.graphFromLegacy(request.triggerName, emptyList(), request.steps)
        val selectedSteps =
            request.nodeId
                ?.let { nodeId -> graph.nodes.filter { node -> node.id == nodeId } }
                ?.filter { node -> node.type == LinearGraphAdapter.NODE_TYPE_ACTION }
                ?.map { node -> WorkflowStepConfig(node.action.orEmpty(), node.params.stringParams()) }
                ?: request.steps
        return WorkflowPreviewResponse(
            scope = scope.workflowStringView(),
            previews = selectedSteps.map { step -> stepRenderer.renderStepPreview(step, scope.workflowStringView()) }
        )
    }

    suspend fun testWorkflowMessage(
        organizationId: Int,
        request: WorkflowPreviewRequest
    ): WorkflowTestMessageResponse {
        val scope = previewScopeForRequest(request)
        val stringScope = scope.workflowStringView()
        return WorkflowTestMessageResponse(
            scope = stringScope,
            results = request.steps.map { step ->
                actionExecutor.sendTestMessageStep(organizationId, step, stringScope)
            }
        )
    }

    private fun previewScopeForRequest(request: WorkflowPreviewRequest): Map<String, JsonElement> {
        val trigger = WorkflowCatalog.trigger(request.triggerName)
            ?: throw IllegalArgumentException("Unknown workflow trigger ${request.triggerName}")
        request.steps.forEach { step ->
            WorkflowCatalog.step(step.name) ?: throw IllegalArgumentException("Unknown workflow step ${step.name}")
        }
        return stepRenderer.sampleScopeForTrigger(trigger.name).typedWorkflowScope() + request.scope
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
                    graph = LinearGraphAdapter.graphFromLegacy(
                        definition.triggerName,
                        emptyList(),
                        definition.steps
                    ),
                    published = true,
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
        val graph = request.graph ?: LinearGraphAdapter.graphFromLegacy(
            request.triggerName,
            request.conditions,
            request.steps
        )
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
                    graph = graph,
                    published = false,
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
            val graph = request.graph ?: if (request.conditions != null || request.steps != null) {
                LinearGraphAdapter.graphFromLegacy(triggerName, conditions, steps)
            } else {
                currentVersion.graph
            }
            val onceForTemplate = request.onceForTemplate ?: currentVersion.onceForTemplate

            validateWorkflowConfig(triggerName, graph, onceForTemplate)
            Workflows.update({ (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }) {
                trimmedName?.let { value -> it[name] = value }
                request.enabled?.let { value -> it[enabled] = value }
                it[updatedAt] = now
            }

            val configChanged =
                request.conditions != null ||
                    request.steps != null ||
                    request.graph != null ||
                    request.onceForTemplate != null
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
                    graph = graph,
                    published = false,
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

    fun publishWorkflow(
        organizationId: Int,
        workflowId: Int
    ): WorkflowResponse? {
        setWorkflowPublished(organizationId, workflowId, true)
        return getWorkflow(organizationId, workflowId)
    }

    fun unpublishWorkflow(
        organizationId: Int,
        workflowId: Int
    ): WorkflowResponse? {
        setWorkflowPublished(organizationId, workflowId, false)
        return getWorkflow(organizationId, workflowId)
    }

    suspend fun runWorkflow(
        organizationId: Int,
        workflowId: Int,
        request: ManualWorkflowRunRequest = ManualWorkflowRunRequest()
    ): WorkflowRunResponse? {
        val run =
            transaction {
                val workflowRow =
                    Workflows
                        .selectAll()
                        .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                        .firstOrNull() ?: return@transaction null
                val version = latestVersion(workflowId) ?: return@transaction null
                val triggerScope = stepRenderer.sampleScopeForTrigger(workflowRow[Workflows.triggerName])
                    .typedWorkflowScope()
                val event =
                    WorkflowTriggerEvent(
                        triggerName = workflowRow[Workflows.triggerName],
                        organizationId = organizationId,
                        scope = triggerScope + request.scope
                    )
                createRun(event, WorkflowCandidate(workflowId, version), manualOnceFor(), force = true)
            } ?: return null
        startTemporalExecution(run)
        return getRun(organizationId, workflowId, run.runId)
    }

    private fun getRun(
        organizationId: Int,
        workflowId: Int,
        runId: Int
    ): WorkflowRunResponse? =
        transaction {
            WorkflowRuns
                .selectAll()
                .where {
                    (WorkflowRuns.id eq runId) and
                        (WorkflowRuns.workflowId eq workflowId) and
                        (WorkflowRuns.organizationId eq organizationId)
                }.firstOrNull()
                ?.let { row -> runResponse(row) }
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
                    ALERT_PRIORITY_REFERENCE to stepRenderer.priorityLabelForSeverity(severity?.name),
                    ALERT_STATUS_REFERENCE to AlertStatus.RESOLVED.name,
                    ALERT_SOURCE_REFERENCE to source,
                    ALERT_DEDUPLICATION_KEY_REFERENCE to deduplicationKey,
                    ALERT_URL_REFERENCE to moneatUrl,
                    ORGANIZATION_ID_REFERENCE to organizationId.toString()
                ).typedWorkflowScope()
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
                            if (version.published) {
                                WorkflowCandidate(workflowRow[Workflows.id].value, version)
                            } else {
                                null
                            }
                        }
                    }
            }

        candidates.forEach { candidate ->
            if (!conditionsMatch(event.triggerName, candidate.version.conditions, event.scope)) return@forEach
            val onceForTemplate =
                candidate.version.onceForTemplate.ifEmpty { trigger.defaultOnceForTemplate }
            val onceFor = buildOnceFor(onceForTemplate, event.scope)
            val run = createRun(event, candidate, onceFor, force = false)
            if (run != null) startTemporalExecution(run)
        }
    }

    suspend fun executeRun(runId: Int) {
        directRunExecutor.executeRun(runId)
    }

    private fun createRun(
        event: WorkflowTriggerEvent,
        candidate: WorkflowCandidate,
        onceFor: String,
        force: Boolean
    ): WorkflowStartRequest? =
        transaction {
            if (!force && workflowRunExists(candidate.workflowId, onceFor)) return@transaction null
            val temporalWorkflowId = temporalWorkflowId(candidate.workflowId, onceFor)
            try {
                val runId = WorkflowRuns.insertAndGetId {
                    it[workflowId] = candidate.workflowId
                    it[workflowVersionId] = candidate.version.id
                    it[organizationId] = event.organizationId
                    it[triggerName] = event.triggerName
                    it[WorkflowRuns.onceFor] = onceFor
                    it[scope] = json.encodeToString(event.scope)
                    it[status] = "pending"
                    it[progress] = "[]"
                    it[WorkflowRuns.temporalWorkflowId] = temporalWorkflowId
                    it[createdAt] = Clock.System.now()
                }.value
                WorkflowStartRequest(
                    runId = runId,
                    workflowId = candidate.workflowId,
                    workflowVersionId = candidate.version.id,
                    organizationId = event.organizationId,
                    triggerName = event.triggerName,
                    onceFor = onceFor,
                    temporalWorkflowId = temporalWorkflowId,
                    scope = event.scope
                )
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

    private fun setWorkflowPublished(
        organizationId: Int,
        workflowId: Int,
        published: Boolean
    ) {
        transaction {
            val hasWorkflow =
                Workflows
                    .selectAll()
                    .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                    .count() > 0
            if (!hasWorkflow) return@transaction
            WorkflowVersions.update(
                { (WorkflowVersions.workflowId eq workflowId) and (WorkflowVersions.mostRecent eq true) }
            ) {
                it[WorkflowVersions.published] = published
            }
        }
    }

    private fun manualOnceFor(): String =
        "manual:${UUID.randomUUID()}"

    private suspend fun startTemporalExecution(request: WorkflowStartRequest) {
        suspendRunCatching {
            executionEngine.start(request)
        }.fold(
            onSuccess = { result ->
                runPersistence.updateTemporalRunId(request.runId, result.temporalRunId)
            },
            onFailure = { error ->
                val message = "Failed to start workflow execution: ${error.message ?: "unknown error"}"
                runPersistence.markFailed(PersistRunFailureInput(request.runId, emptyList(), message))
                logger.error(error) { "Failed to start workflow run ${request.runId}" }
            }
        )
    }

    private fun temporalWorkflowId(
        workflowId: Int,
        onceFor: String
    ): String =
        "wf-$workflowId-${sha256(onceFor).take(TEMPORAL_WORKFLOW_ID_HASH_LENGTH)}"

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }

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
        graph: WorkflowGraphConfig,
        published: Boolean,
        onceForTemplate: List<String>,
        now: kotlin.time.Instant
    ) {
        WorkflowVersions.insertAndGetId {
            it[WorkflowVersions.workflowId] = workflowId
            it[WorkflowVersions.version] = version
            it[WorkflowVersions.conditions] = json.encodeToString(conditions)
            it[WorkflowVersions.steps] = json.encodeToString(steps)
            it[WorkflowVersions.graph] = json.encodeToString(graph)
            it[WorkflowVersions.published] = published
            it[WorkflowVersions.inputSchema] = "{}"
            it[WorkflowVersions.tags] = "[]"
            it[WorkflowVersions.onceForTemplate] = json.encodeToString(onceForTemplate)
            it[engineConfig] = json.encodeToString(buildEngineConfig(conditions, steps, graph, onceForTemplate))
            it[mostRecent] = true
            it[createdAt] = now
        }
    }

    private fun validateCreateRequest(request: CreateWorkflowRequest) {
        require(request.name.isNotBlank()) { "Workflow name is required" }
        val graph = request.graph ?: LinearGraphAdapter.graphFromLegacy(
            request.triggerName,
            request.conditions,
            request.steps
        )
        validateWorkflowConfig(request.triggerName, graph, request.onceForTemplate)
    }

    private fun validateWorkflowConfig(
        triggerName: String,
        graph: WorkflowGraphConfig,
        onceForTemplate: List<String>
    ) {
        graphValidator.validate(triggerName, graph, onceForTemplate)
    }

    private fun conditionsMatch(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>,
        scope: Map<String, JsonElement>
    ): Boolean =
        WorkflowConditionEvaluator.matchesAll(triggerName, conditions, scope)

    private fun buildOnceFor(
        onceForTemplate: List<String>,
        scope: Map<String, JsonElement>
    ): String =
        onceForTemplate
            .ifEmpty { listOf(ALERT_DEDUPLICATION_KEY_REFERENCE) }
            .joinToString("|") { reference -> "$reference=${scope[reference]?.workflowStringValue().orEmpty()}" }

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
            published = version.published,
            systemKey = row[Workflows.systemKey],
            conditions = version.conditions,
            steps = version.steps,
            graph = version.graph,
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
            steps = runSteps(row[WorkflowRuns.id].value),
            errorMessage = row[WorkflowRuns.errorMessage],
            createdAt = row[WorkflowRuns.createdAt].toString(),
            completedAt = row[WorkflowRuns.completedAt]?.toString(),
            failedAt = row[WorkflowRuns.failedAt]?.toString()
        )

    private fun runSteps(runId: Int): List<WorkflowRunStepResponse> =
        WorkflowRunSteps
            .selectAll()
            .where { WorkflowRunSteps.runId eq runId }
            .orderBy(WorkflowRunSteps.id to SortOrder.ASC)
            .map { row ->
                WorkflowRunStepResponse(
                    id = row[WorkflowRunSteps.id].value,
                    runId = row[WorkflowRunSteps.runId],
                    nodeId = row[WorkflowRunSteps.nodeId],
                    type = row[WorkflowRunSteps.type],
                    status = row[WorkflowRunSteps.status],
                    startedAt = row[WorkflowRunSteps.startedAt]?.toString(),
                    completedAt = row[WorkflowRunSteps.completedAt]?.toString(),
                    input = decodeJsonElementMap(row[WorkflowRunSteps.input]),
                    output = decodeJsonElementMap(row[WorkflowRunSteps.output]),
                    errorMessage = row[WorkflowRunSteps.errorMessage],
                    attempt = row[WorkflowRunSteps.attempt]
                )
            }

    private fun versionRecord(row: ResultRow): WorkflowVersionRecord =
        WorkflowVersionRecord(
            id = row[WorkflowVersions.id].value,
            version = row[WorkflowVersions.version],
            conditions = decodeConditions(row[WorkflowVersions.conditions]),
            steps = decodeSteps(row[WorkflowVersions.steps]),
            graph = decodeGraph(row[WorkflowVersions.graph]),
            published = row[WorkflowVersions.published],
            onceForTemplate = decodeStringList(row[WorkflowVersions.onceForTemplate])
        )

    private fun decodeConditions(raw: String): List<WorkflowConditionConfig> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeSteps(raw: String): List<WorkflowStepConfig> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeGraph(raw: String): WorkflowGraphConfig =
        if (raw.isBlank()) WorkflowGraphConfig() else json.decodeFromString(raw)

    private fun decodeStringList(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeProgress(raw: String): List<WorkflowRunStepProgress> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun decodeScope(raw: String): Map<String, JsonElement> =
        if (raw.isBlank()) emptyMap() else json.decodeFromString(raw)

    private fun decodeJsonElementMap(raw: String): Map<String, JsonElement> =
        if (raw.isBlank()) emptyMap() else json.decodeFromString<JsonElement>(raw).workflowObjectValue()

    private fun Map<String, JsonElement>.stringParams(): Map<String, String> =
        mapValues { (_, value) -> value.workflowStringValue() }

    private fun buildEngineConfig(
        conditions: List<WorkflowConditionConfig>,
        steps: List<WorkflowStepConfig>,
        graph: WorkflowGraphConfig,
        onceForTemplate: List<String>
    ): List<String> =
        (
            conditions.map { it.reference } +
                graph.nodes.flatMap { node -> node.conditions.map { condition -> condition.reference } } +
                onceForTemplate +
                steps.flatMap { it.params.values } +
                graph.nodes.flatMap { node -> node.params.values.map { value -> value.workflowStringValue() } }
            )
            .filter { it.startsWith("alert.") || it.startsWith("organization.") }
            .distinct()

    private fun alertScope(event: AlertLifecycleEvent): Map<String, JsonElement> {
        val baseScope =
            mapOf(
                ALERT_TITLE_REFERENCE to event.title,
                ALERT_DESCRIPTION_REFERENCE to event.description,
                ALERT_SEVERITY_REFERENCE to event.severity.name,
                ALERT_PRIORITY_REFERENCE to stepRenderer.priorityLabelForSeverity(event.severity.name),
                ALERT_STATUS_REFERENCE to event.status.name,
                ALERT_SOURCE_REFERENCE to event.source.name,
                ALERT_DEDUPLICATION_KEY_REFERENCE to event.deduplicationKey,
                ALERT_URL_REFERENCE to event.moneatUrl,
                ORGANIZATION_ID_REFERENCE to event.organizationId.toString()
            ).typedWorkflowScope()
        return baseScope + alertMetadataScope(event.metadata)
    }

    private fun alertMetadataScope(metadata: Map<String, JsonElement>): Map<String, JsonElement> =
        metadata
            .mapNotNull { (reference, value) ->
                if (reference !in ALERT_METADATA_REFERENCES) return@mapNotNull null
                value.workflowScopeValue()?.let { reference to JsonPrimitive(it) }
            }.toMap()

    private fun JsonElement.workflowScopeValue(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.booleanOrNull?.toString() ?: primitive.contentOrNull
    }

    companion object {
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
                                "body" to "{{alert.display_title}}\n\n" + ALERT_DESCRIPTION_TEMPLATE_BLOCK +
                                    ALERT_PRIORITY_TEMPLATE_LINE +
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
                                "message" to ALERT_DESCRIPTION_TEMPLATE_BLOCK +
                                    ALERT_PRIORITY_TEMPLATE_LINE +
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
                                "body" to "{{alert.display_title}}\n\n" + ALERT_DESCRIPTION_TEMPLATE_BLOCK +
                                    ALERT_PRIORITY_TEMPLATE_LINE +
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
                                    ALERT_DESCRIPTION_TEMPLATE_BLOCK +
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
                                "message" to ALERT_DESCRIPTION_TEMPLATE_BLOCK +
                                    ALERT_PRIORITY_TEMPLATE_LINE +
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
    val graph: WorkflowGraphConfig,
    val published: Boolean,
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
