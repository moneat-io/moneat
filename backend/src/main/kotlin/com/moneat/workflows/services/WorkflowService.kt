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
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.IncidentSeverity
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertEpisodeContext
import com.moneat.alerts.services.AlertEpisodeDecision
import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.alerts.services.AlertWorkflowFanout
import com.moneat.config.EnvConfig
import com.moneat.monitoring.OperationalMetrics
import com.moneat.security.signals.SignalOutcome
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.OnCallIncidents
import com.moneat.shared.models.Organizations
import com.moneat.shared.services.organizationResourceId
import com.moneat.shared.services.resolveGlobalIntResourceId
import com.moneat.shared.services.resolveScopedIntResourceId
import com.moneat.shared.services.toUuidOrNull
import com.moneat.shared.services.userResourceId
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
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.ManualWorkflowRunRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowPreviewRequest
import com.moneat.workflows.models.WorkflowPreviewResponse
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunCancelResponse
import com.moneat.workflows.models.WorkflowRunInstanceRequest
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.models.WorkflowRunStepResponse
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowRunSteps
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowTestMessageResponse
import com.moneat.workflows.models.WorkflowTriggerEvent
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.WorkflowWebhookSigningResponse
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
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.UUID
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val TEMPORAL_WORKFLOW_ID_HASH_LENGTH = 32
private const val UNSIGNED_BYTE_MASK = 0xff
private const val HMAC_ALGORITHM = "HmacSHA256"
private const val WORKFLOW_WEBHOOK_SECRET_CONTEXT = "workflow-webhook"
private const val WORKFLOW_WEBHOOK_SIGNATURE_HEADER = "X-Moneat-Workflow-Signature"
private const val WEBHOOK_SIGNATURE_PREFIX = "sha256="
private const val WEBHOOK_PAYLOAD_MAX_CHARS = 32_000
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

data class AlertResolvedWorkflowEvent(
    val organizationId: Int,
    val source: String,
    val deduplicationKey: String,
    val title: String = "Alert resolved",
    val description: String = "Moneat resolved alert $deduplicationKey",
    val moneatUrl: String = "",
    val priority: AlertPriority? = null,
    val severity: AlertPriority? = null,
)

data class DeclaredIncidentRoleChange(
    val organizationId: Int,
    val incidentId: Int,
    val title: String,
    val severity: IncidentSeverity?,
    val role: String,
    val assignee: String?,
    val action: String,
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
    private val executionEngine: WorkflowExecutionEngine = TemporalWorkflowExecutionEngine(TemporalClientProvider()),
    private val alertEpisodeService: AlertEpisodeService = AlertEpisodeService()
) : AlertWorkflowFanout {
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

    fun resolveWorkflowId(organizationId: Int, workflowResourceId: Uuid): Int? =
        resolveScopedIntResourceId(
            table = Workflows,
            resourceIdColumn = Workflows.resourceId,
            scopeColumn = Workflows.organizationId,
            scopeId = organizationId,
            resourceId = workflowResourceId,
        )

    fun resolveWorkflowId(organizationId: Int, workflowResourceId: String): Int? =
        parseResourceId(workflowResourceId)?.let { resolveWorkflowId(organizationId, it) }

    fun resolveWorkflowId(workflowResourceId: Uuid): Int? =
        resolveGlobalIntResourceId(
            table = Workflows,
            resourceIdColumn = Workflows.resourceId,
            resourceId = workflowResourceId,
        )

    fun resolveWorkflowId(workflowResourceId: String): Int? =
        parseResourceId(workflowResourceId)?.let(::resolveWorkflowId)

    fun resolveRunId(
        organizationId: Int,
        workflowId: Int,
        runResourceId: Uuid
    ): Int? =
        transaction {
            WorkflowRuns
                .selectAll()
                .where {
                    (WorkflowRuns.resourceId eq runResourceId) and
                        (WorkflowRuns.workflowId eq workflowId) and
                        (WorkflowRuns.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(WorkflowRuns.id)
                ?.value
        }

    fun resolveRunId(
        organizationId: Int,
        workflowId: Int,
        runResourceId: String
    ): Int? =
        parseResourceId(runResourceId)?.let { resolveRunId(organizationId, workflowId, it) }

    fun resolveRunId(runResourceId: String): Int? =
        parseResourceId(runResourceId)?.let { parsedId ->
            transaction {
                WorkflowRuns
                    .selectAll()
                    .where { WorkflowRuns.resourceId eq parsedId }
                    .firstOrNull()
                    ?.get(WorkflowRuns.id)
                    ?.value
            }
        }

    private fun parseResourceId(value: String): Uuid? =
        value.toUuidOrNull()

    fun getWorkflow(
        organizationId: Int,
        workflowId: String
    ): WorkflowResponse? =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> getWorkflow(organizationId, resolvedWorkflowId) }

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
                WorkflowAudit.recordInTransaction(
                    organizationId = organizationId,
                    action = WorkflowAudit.ACTION_CREATED,
                    workflowId = id
                )
                id
            }
        return checkNotNull(getWorkflow(organizationId, workflowId))
    }

    fun updateWorkflow(
        organizationId: Int,
        workflowId: String,
        request: UpdateWorkflowRequest
    ): WorkflowResponse? =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> updateWorkflow(organizationId, resolvedWorkflowId, request) }

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
            WorkflowAudit.recordInTransaction(
                organizationId = organizationId,
                action = WorkflowAudit.ACTION_UPDATED,
                workflowId = workflowId
            )
        }
        return getWorkflow(organizationId, workflowId)
    }

    fun deleteWorkflow(
        organizationId: Int,
        workflowId: String
    ): Boolean =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> deleteWorkflow(organizationId, resolvedWorkflowId) } ?: false

    fun deleteWorkflow(
        organizationId: Int,
        workflowId: Int
    ): Boolean {
        val workflowResourceId =
            transaction {
                val workflowRow =
                    Workflows
                        .selectAll()
                        .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                        .firstOrNull() ?: return@transaction null
                require(workflowRow[Workflows.systemKey] == null) { DEFAULT_WORKFLOW_READ_ONLY_MESSAGE }
                workflowRow[Workflows.resourceId].toString()
            } ?: return false
        return transaction {
            val deleted =
                Workflows.deleteWhere {
                    (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId)
                } > 0
            if (deleted) {
                // workflow_id stays null because the workflow row is being removed in
                // this same transaction (cascade would otherwise drop the audit row).
                WorkflowAudit.recordInTransaction(
                    organizationId = organizationId,
                    action = WorkflowAudit.ACTION_DELETED,
                    workflowId = null,
                    detail = mapOf("workflow_id" to workflowResourceId)
                )
            }
            deleted
        }
    }

    fun publishWorkflow(
        organizationId: Int,
        workflowId: String
    ): WorkflowResponse? =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> publishWorkflow(organizationId, resolvedWorkflowId) }

    fun publishWorkflow(
        organizationId: Int,
        workflowId: Int
    ): WorkflowResponse? {
        setWorkflowPublished(organizationId, workflowId, true)
        return getWorkflow(organizationId, workflowId)
    }

    fun unpublishWorkflow(
        organizationId: Int,
        workflowId: String
    ): WorkflowResponse? =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> unpublishWorkflow(organizationId, resolvedWorkflowId) }

    fun unpublishWorkflow(
        organizationId: Int,
        workflowId: Int
    ): WorkflowResponse? {
        setWorkflowPublished(organizationId, workflowId, false)
        return getWorkflow(organizationId, workflowId)
    }

    suspend fun runWorkflow(
        organizationId: Int,
        workflowId: String,
        request: ManualWorkflowRunRequest = ManualWorkflowRunRequest(),
        actorUserId: Int? = null
    ): WorkflowRunResponse? =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> runWorkflow(organizationId, resolvedWorkflowId, request, actorUserId) }

    suspend fun runWorkflow(
        organizationId: Int,
        workflowId: Int,
        request: ManualWorkflowRunRequest = ManualWorkflowRunRequest(),
        actorUserId: Int? = null
    ): WorkflowRunResponse? {
        val manualScope =
            mapOf(
                WORKFLOW_INPUT_REFERENCE to json.encodeToString(request.scope),
                WORKFLOW_ACTOR_ID_REFERENCE to actorUserId?.let(::userResourceId).orEmpty(),
                ORGANIZATION_ID_REFERENCE to organizationResourceId(organizationId)
            ).typedWorkflowScope()
        val run = createRunForWorkflow(
            organizationId = organizationId,
            workflowId = workflowId,
            scope = manualScope + (request.scope - manualScope.keys),
            onceFor = manualOnceFor(),
            force = true
        ) ?: return null
        startTemporalExecution(run)
        return getRun(organizationId, workflowId, run.runId)
    }

    suspend fun createWorkflowInstance(
        organizationId: Int,
        workflowId: String,
        request: WorkflowRunInstanceRequest,
        callerUserId: Int
    ): WorkflowRunResponse? =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId ->
                createWorkflowInstance(organizationId, resolvedWorkflowId, request, callerUserId)
            }

    suspend fun createWorkflowInstance(
        organizationId: Int,
        workflowId: Int,
        request: WorkflowRunInstanceRequest,
        callerUserId: Int
    ): WorkflowRunResponse? {
        val apiScope =
            mapOf(
                WORKFLOW_INPUT_REFERENCE to json.encodeToString(request.scope),
                WORKFLOW_CALLER_REFERENCE to userResourceId(callerUserId),
                ORGANIZATION_ID_REFERENCE to organizationResourceId(organizationId)
            ).typedWorkflowScope()
        val run = createRunForWorkflow(
            organizationId = organizationId,
            workflowId = workflowId,
            scope = apiScope + (request.scope - apiScope.keys),
            onceFor = apiOnceFor(),
            force = true,
            gate = TriggerGate(requiredTriggerName = API_TRIGGER, applyConditions = true)
        ) ?: return null
        startTemporalExecution(run)
        return getRun(organizationId, workflowId, run.runId)
    }

    suspend fun createWebhookRun(
        workflowId: String,
        payload: String,
        eventId: String?
    ): WorkflowRunResponse? =
        resolveWorkflowId(workflowId)
            ?.let { resolvedWorkflowId -> createWebhookRun(resolvedWorkflowId, payload, eventId) }

    suspend fun createWebhookRun(
        workflowId: Int,
        payload: String,
        eventId: String?
    ): WorkflowRunResponse? {
        val sanitizedPayload = payload.take(WEBHOOK_PAYLOAD_MAX_CHARS)
        val workflowRef = workflowReference(workflowId) ?: return null
        val webhookScope =
            mapOf(
                WEBHOOK_PAYLOAD_REFERENCE to sanitizedPayload,
                WEBHOOK_EVENT_ID_REFERENCE to (eventId ?: webhookOnceFor()),
                ORGANIZATION_ID_REFERENCE to organizationResourceId(workflowRef.organizationId)
            ).typedWorkflowScope()
        val run = createRunForWorkflow(
            organizationId = workflowRef.organizationId,
            workflowId = workflowId,
            scope = webhookScope,
            onceFor = webhookScope[WEBHOOK_EVENT_ID_REFERENCE]?.workflowStringValue().orEmpty(),
            force = false,
            gate = TriggerGate(
                requiredTriggerName = WEBHOOK_TRIGGER,
                requirePublished = true,
                applyConditions = true
            )
        ) ?: return null
        startTemporalExecution(run)
        return getRun(workflowRef.organizationId, workflowId, run.runId)
    }

    fun getRun(
        organizationId: Int,
        workflowId: String,
        runId: String
    ): WorkflowRunResponse? {
        val resolvedWorkflowId = resolveWorkflowId(organizationId, workflowId) ?: return null
        val resolvedRunId = resolveRunId(organizationId, resolvedWorkflowId, runId) ?: return null
        return getRun(organizationId, resolvedWorkflowId, resolvedRunId)
    }

    fun getRun(
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

    suspend fun cancelRun(
        organizationId: Int,
        workflowId: String,
        runId: String
    ): WorkflowRunCancelResponse? {
        val resolvedWorkflowId = resolveWorkflowId(organizationId, workflowId) ?: return null
        val resolvedRunId = resolveRunId(organizationId, resolvedWorkflowId, runId) ?: return null
        return cancelRun(organizationId, resolvedWorkflowId, resolvedRunId)
    }

    suspend fun cancelRun(
        organizationId: Int,
        workflowId: Int,
        runId: Int
    ): WorkflowRunCancelResponse? {
        val candidate =
            transaction {
                val row =
                    WorkflowRuns
                        .selectAll()
                        .where {
                            (WorkflowRuns.id eq runId) and
                                (WorkflowRuns.workflowId eq workflowId) and
                                (WorkflowRuns.organizationId eq organizationId)
                        }.firstOrNull() ?: return@transaction null
                WorkflowRunCancelCandidate(
                    status = row[WorkflowRuns.status],
                    temporalWorkflowId = row[WorkflowRuns.temporalWorkflowId].orEmpty(),
                    resourceId = row[WorkflowRuns.resourceId]
                )
            } ?: return null
        if (candidate.status in TERMINAL_RUN_STATUSES) {
            return WorkflowRunCancelResponse(candidate.resourceId.toString(), candidate.status)
        }
        val temporalWorkflowId = candidate.temporalWorkflowId
        if (temporalWorkflowId.isNotBlank()) {
            suspendRunCatching {
                executionEngine.cancel(temporalWorkflowId)
            }.onFailure { error ->
                logger.warn(error) { "Failed to cancel Temporal workflow $temporalWorkflowId" }
            }
        }
        val updated =
            transaction {
                WorkflowRuns.update(
                    {
                        (WorkflowRuns.id eq runId) and (WorkflowRuns.status notInList TERMINAL_RUN_STATUSES)
                    }
                ) {
                    it[status] = STATUS_CANCELED
                    it[completedAt] = Clock.System.now()
                    it[errorMessage] = null
                }
            }
        if (updated > 0) {
            return WorkflowRunCancelResponse(candidate.resourceId.toString(), STATUS_CANCELED)
        }
        // The run reached a terminal state between the initial read and this write; report its real status.
        val currentStatus =
            transaction {
                WorkflowRuns
                    .selectAll()
                    .where { WorkflowRuns.id eq runId }
                    .firstOrNull()
                    ?.get(WorkflowRuns.status)
            }
        return WorkflowRunCancelResponse(candidate.resourceId.toString(), currentStatus ?: STATUS_CANCELED)
    }

    fun listRuns(
        organizationId: Int,
        workflowId: String,
        limit: Int = 50
    ): List<WorkflowRunResponse> =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> listRuns(organizationId, resolvedWorkflowId, limit) } ?: emptyList()

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

            val rows = WorkflowRuns
                .selectAll()
                .where {
                    (WorkflowRuns.workflowId eq workflowId) and (WorkflowRuns.organizationId eq organizationId)
                }.orderBy(WorkflowRuns.createdAt to SortOrder.DESC)
                .limit(limit)
                .toList()
            val workflowResourceIds = workflowResourceIds(rows.map { row -> row[WorkflowRuns.workflowId] })
            val versionResourceIds = workflowVersionResourceIds(rows.map { row -> row[WorkflowRuns.workflowVersionId] })
            rows.map { row -> runResponse(row, workflowResourceIds, versionResourceIds) }
        }

    fun webhookSigningInfo(
        organizationId: Int,
        workflowId: String
    ): WorkflowWebhookSigningResponse? =
        resolveWorkflowId(organizationId, workflowId)
            ?.let { resolvedWorkflowId -> webhookSigningInfo(organizationId, resolvedWorkflowId) }

    fun webhookSigningInfo(
        organizationId: Int,
        workflowId: Int
    ): WorkflowWebhookSigningResponse? {
        workflowReference(workflowId)
            ?.takeIf { it.organizationId == organizationId && it.triggerName == WEBHOOK_TRIGGER }
            ?: return null
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io").trimEnd('/')
        val workflowResourceId = workflowReference(workflowId)?.resourceId?.toString() ?: return null
        return WorkflowWebhookSigningResponse(
            workflowId = workflowResourceId,
            webhookUrl = "$backendUrl/v1/workflows/$workflowResourceId/webhook",
            signingSecret = webhookSecret(workflowId),
            signatureHeader = WORKFLOW_WEBHOOK_SIGNATURE_HEADER,
            signatureFormat = "$WEBHOOK_SIGNATURE_PREFIX<hex HMAC-SHA256 of raw body>"
        )
    }

    fun verifyWebhookSignature(
        workflowId: Int,
        payload: String,
        signatureHeader: String?
    ): Boolean {
        workflowReference(workflowId)
            ?.takeIf { it.triggerName == WEBHOOK_TRIGGER }
            ?: return false
        val provided = signatureHeader?.trim()?.removePrefix(WEBHOOK_SIGNATURE_PREFIX) ?: return false
        val expected = hmacSha256(webhookSecret(workflowId), payload)
        return MessageDigest.isEqual(provided.encodeToByteArray(), expected.encodeToByteArray())
    }

    fun verifyWebhookSignature(
        workflowId: String,
        payload: String,
        signatureHeader: String?
    ): Boolean =
        resolveWorkflowId(workflowId)
            ?.let { resolvedWorkflowId -> verifyWebhookSignature(resolvedWorkflowId, payload, signatureHeader) }
            ?: false

    /**
     * Publish an alert lifecycle event when the episode is due for notification.
     *
     * The return value lets callers that fan out to external incident providers
     * share the same episode/reminder gate instead of sending a provider alert
     * for every evaluator pass.
     */
    suspend fun publishAlertTriggered(event: AlertLifecycleEvent): Boolean {
        if (event.status == AlertStatus.RESOLVED) {
            return publishResolvedAlertEvent(event)
        }
        if (isAlertDeliverySilenced(event.organizationId)) {
            alertEpisodeService.recordFiringWithoutNotification(event)
            logger.info {
                "Alert delivery suppressed by an active silence for organization ${event.organizationId}"
            }
            return false
        }
        val episode = alertEpisodeService.recordFiring(event) ?: return true
        if (!episode.shouldPublish) return false
        suspendRunCatching {
            publishAlertWorkflowTriggers(event, episode)
        }.onFailure { e ->
            logger.error(e) { "Failed to publish alert workflow triggers" }
        }
        return true
    }

    /** Publish from the shared episode-first orchestration seam without recording the episode again. */
    override suspend fun publish(context: AlertFanoutContext) {
        val decision = context.episodeDecision ?: return
        publishAlertWorkflowTriggers(context.event, decision)
    }

    private fun isAlertDeliverySilenced(organizationId: Int): Boolean =
        runCatching {
            alertEpisodeService.isOrganizationSilenced(organizationId)
        }.getOrElse { error ->
            logger.error(error) {
                "Failed to check alert silence for organization $organizationId; delivering alert"
            }
            false
        }

    private suspend fun publishResolvedAlertEvent(event: AlertLifecycleEvent): Boolean {
        val episode = alertEpisodeService.recordResolved(event)
        if (isAlertDeliverySilenced(event.organizationId)) {
            logger.info {
                "Resolved alert delivery suppressed by an active silence for organization ${event.organizationId}"
            }
            return false
        }
        if (episode == null) return true
        if (!episode.shouldPublish) return false
        suspendRunCatching {
            publishAlertWorkflowTriggers(event, episode)
        }.onFailure { e ->
            logger.error(e) { "Failed to publish resolved alert workflow triggers" }
        }
        return true
    }

    private suspend fun publishAlertWorkflowTriggers(
        event: AlertLifecycleEvent,
        episode: AlertEpisodeDecision
    ) {
        val triggerName =
            if (event.status == AlertStatus.RESOLVED) {
                ALERT_RESOLVED_TRIGGER
            } else {
                ALERT_TRIGGERED_TRIGGER
            }
        val scope = alertScope(event, episode)
        publishTrigger(WorkflowTriggerEvent(triggerName, event.organizationId, scope))
        sourceSpecificAlertTrigger(event)?.let { specificTriggerName ->
            publishTrigger(
                WorkflowTriggerEvent(
                    triggerName = specificTriggerName,
                    organizationId = event.organizationId,
                    scope = scope
                )
            )
        }
    }

    suspend fun publishAlertResolved(event: AlertResolvedWorkflowEvent) {
        val alertSource = AlertSource.entries.firstOrNull { it.name == event.source }
        if (alertSource == null) {
            logger.warn {
                "Skipping resolved alert workflow for unknown AlertSource '${event.source}' " +
                    "with deduplicationKey='${event.deduplicationKey}' and organizationId=${event.organizationId}"
            }
            return
        }
        publishResolvedAlertEvent(
            AlertLifecycleEvent(
                title = event.title,
                description = event.description,
                priority = event.priority ?: event.severity ?: AlertPriority.P3,
                status = AlertStatus.RESOLVED,
                source = alertSource,
                deduplicationKey = event.deduplicationKey,
                organizationId = event.organizationId,
                moneatUrl = event.moneatUrl
            )
        )
    }

    private fun declaredIncidentResourceId(
        organizationId: Int,
        incidentId: Int
    ): String =
        transaction {
            OnCallIncidents
                .selectAll()
                .where {
                    (OnCallIncidents.id eq incidentId) and
                        (OnCallIncidents.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(OnCallIncidents.resourceId)
                ?.toString()
        } ?: error("Missing resource_id for on-call incident $incidentId in organization $organizationId")

    suspend fun publishIncidentCreated(
        event: AlertLifecycleEvent,
        severity: IncidentSeverity = IncidentSeverity.SEV2
    ) {
        val episode = alertEpisodeService.ensureOpenEpisodeForWorkflow(event) ?: return
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = INCIDENT_CREATED_TRIGGER,
                organizationId = event.organizationId,
                scope = incidentScope(
                    organizationId = event.organizationId,
                    status = INCIDENT_STATUS_CREATED,
                    title = event.title,
                    severity = severity.wire,
                    deduplicationKey = event.deduplicationKey,
                    episode = episode
                )
            )
        )
    }

    suspend fun publishIncidentResolved(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        title: String,
        severity: IncidentSeverity? = null
    ) {
        val episode = alertEpisodeService.latestEpisodeForWorkflow(
            organizationId = organizationId,
            source = source,
            deduplicationKey = deduplicationKey
        ) ?: return
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = INCIDENT_RESOLVED_TRIGGER,
                organizationId = organizationId,
                scope = incidentScope(
                    organizationId = organizationId,
                    status = INCIDENT_STATUS_RESOLVED,
                    title = title,
                    severity = severity?.wire.orEmpty(),
                    deduplicationKey = deduplicationKey,
                    episode = episode
                )
            )
        )
    }

    suspend fun publishDeclaredIncidentCreated(
        organizationId: Int,
        incidentId: Int,
        title: String,
        severity: IncidentSeverity
    ) {
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = INCIDENT_CREATED_TRIGGER,
                organizationId = organizationId,
                scope = declaredIncidentScope(
                    organizationId = organizationId,
                    incidentId = incidentId,
                    status = INCIDENT_STATUS_CREATED,
                    title = title,
                    severity = severity
                )
            )
        )
    }

    suspend fun publishDeclaredIncidentResolved(
        organizationId: Int,
        incidentId: Int,
        title: String,
        severity: IncidentSeverity
    ) {
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = INCIDENT_RESOLVED_TRIGGER,
                organizationId = organizationId,
                scope = declaredIncidentScope(
                    organizationId = organizationId,
                    incidentId = incidentId,
                    status = INCIDENT_STATUS_RESOLVED,
                    title = title,
                    severity = severity
                )
            )
        )
    }

    suspend fun publishDeclaredIncidentRoleChanged(change: DeclaredIncidentRoleChange) {
        publishTrigger(
            WorkflowTriggerEvent(
                triggerName = INCIDENT_ROLE_CHANGED_TRIGGER,
                organizationId = change.organizationId,
                scope = declaredIncidentRoleScope(
                    change = change,
                ),
            ),
        )
    }

    /**
     * Fires the `security.signal` workflow trigger once per **newly created or escalated** signal,
     * not once per raw agent event — eliminating the previous per-event fan-out. Repeats that merely
     * fold into an existing open signal (`Updated`) intentionally do not re-trigger.
     */
    suspend fun publishSecuritySignals(organizationId: Int, signals: List<SignalOutcome>) {
        if (isAlertDeliverySilenced(organizationId)) {
            logger.info {
                "Security signal delivery suppressed by an active silence for organization $organizationId"
            }
            return
        }
        signals
            .filter { it is SignalOutcome.Created || it is SignalOutcome.Escalated }
            .forEach { signal ->
                publishTrigger(
                    WorkflowTriggerEvent(
                        triggerName = SECURITY_SIGNAL_TRIGGER,
                        organizationId = organizationId,
                        scope = securitySignalScope(organizationId, signal)
                    )
                )
            }
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
            if (WorkflowRateLimiter.isLimited(candidate.workflowId, candidate.version.graph)) {
                OperationalMetrics.recordWorkflowRateLimited(event.triggerName)
                logger.debug { "Workflow ${candidate.workflowId} rate limited for trigger ${event.triggerName}" }
                return@forEach
            }
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

    suspend fun executeRun(runId: String) {
        val resolvedRunId = resolveRunId(runId) ?: return
        executeRun(resolvedRunId)
    }

    private fun createRunForWorkflow(
        organizationId: Int,
        workflowId: Int,
        scope: Map<String, JsonElement>,
        onceFor: String,
        force: Boolean,
        gate: TriggerGate = TriggerGate()
    ): WorkflowStartRequest? =
        transaction {
            val workflowRow =
                Workflows
                    .selectAll()
                    .where { (Workflows.id eq workflowId) and (Workflows.organizationId eq organizationId) }
                    .firstOrNull() ?: return@transaction null
            if (gate.requiredTriggerName != null && workflowRow[Workflows.triggerName] != gate.requiredTriggerName) {
                return@transaction null
            }
            val version = latestVersion(workflowId) ?: return@transaction null
            if (gate.requirePublished && !version.published) return@transaction null
            val triggerScope = stepRenderer.sampleScopeForTrigger(workflowRow[Workflows.triggerName])
                .typedWorkflowScope()
            val event =
                WorkflowTriggerEvent(
                    triggerName = workflowRow[Workflows.triggerName],
                    organizationId = organizationId,
                    scope = triggerScope + scope
                )
            // Manual runs intentionally bypass conditions (they run against synthetic sample
            // scope); API and signed-webhook runs carry real input and honor configured conditions.
            if (gate.applyConditions && !conditionsMatch(event.triggerName, version.conditions, event.scope)) {
                return@transaction null
            }
            createRun(event, WorkflowCandidate(workflowId, version), onceFor, force)
        }

    private fun createRun(
        event: WorkflowTriggerEvent,
        candidate: WorkflowCandidate,
        onceFor: String,
        force: Boolean
    ): WorkflowStartRequest? =
        transaction {
            if (!force && workflowRunExists(candidate.workflowId, onceFor)) return@transaction null
            if (refuseForQuota(event, candidate.workflowId)) return@transaction null
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
                WorkflowAudit.recordInTransaction(
                    organizationId = event.organizationId,
                    action = WorkflowAudit.ACTION_RUN_STARTED,
                    workflowId = candidate.workflowId,
                    runId = runId
                )
                OperationalMetrics.recordWorkflowExecutionStarted(event.triggerName)
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

    private fun refuseForQuota(
        event: WorkflowTriggerEvent,
        workflowId: Int
    ): Boolean {
        if (!WorkflowUsage.isOverQuota(event.organizationId, Clock.System.now())) return false
        WorkflowAudit.recordInTransaction(
            organizationId = event.organizationId,
            action = WorkflowAudit.ACTION_RUN_REFUSED,
            workflowId = workflowId,
            detail = mapOf("reason" to "monthly_quota_exceeded")
        )
        WorkflowUsage.recordRefused(
            organizationId = event.organizationId,
            workflowId = workflowId,
            now = Clock.System.now()
        )
        logger.info { "Refused workflow $workflowId run: monthly execution quota exceeded" }
        return true
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
            WorkflowAudit.recordInTransaction(
                organizationId = organizationId,
                action = if (published) WorkflowAudit.ACTION_PUBLISHED else WorkflowAudit.ACTION_UNPUBLISHED,
                workflowId = workflowId
            )
        }
    }

    private fun manualOnceFor(): String =
        "manual:${UUID.randomUUID()}"

    private fun apiOnceFor(): String =
        "api:${UUID.randomUUID()}"

    private fun webhookOnceFor(): String =
        "webhook:${UUID.randomUUID()}"

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

    private fun webhookSecret(workflowId: Int): String =
        hmacSha256(workflowSigningKey(), "$WORKFLOW_WEBHOOK_SECRET_CONTEXT:$workflowId")

    private fun workflowSigningKey(): String =
        EnvConfig.get("WORKFLOWS_SIGNING_KEY")
            ?: throw IllegalStateException("WORKFLOWS_SIGNING_KEY is required for workflow webhook signing")

    private fun hmacSha256(
        key: String,
        value: String
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key.encodeToByteArray(), HMAC_ALGORITHM))
        return mac
            .doFinal(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }
    }

    private fun workflowReference(workflowId: Int): WorkflowReference? =
        transaction {
            Workflows
                .selectAll()
                .where { Workflows.id eq workflowId }
                .firstOrNull()
                ?.let { row ->
                    WorkflowReference(
                        id = row[Workflows.id].value,
                        resourceId = row[Workflows.resourceId],
                        organizationId = row[Workflows.organizationId],
                        triggerName = row[Workflows.triggerName]
                    )
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
            id = row[Workflows.resourceId].toString(),
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

    private fun runResponse(
        row: ResultRow,
        workflowIdsByPk: Map<Int, String> = workflowResourceIds(listOf(row[WorkflowRuns.workflowId])),
        versionIdsByPk: Map<Int, String> = workflowVersionResourceIds(listOf(row[WorkflowRuns.workflowVersionId])),
    ): WorkflowRunResponse =
        WorkflowRunResponse(
            id = row[WorkflowRuns.resourceId].toString(),
            workflowId = workflowIdsByPk.requireWorkflowResourceId(row[WorkflowRuns.workflowId], "workflow"),
            workflowVersionId = versionIdsByPk.requireWorkflowResourceId(
                row[WorkflowRuns.workflowVersionId],
                "workflow version"
            ),
            triggerName = row[WorkflowRuns.triggerName],
            onceFor = row[WorkflowRuns.onceFor],
            status = row[WorkflowRuns.status],
            progress = decodeProgress(row[WorkflowRuns.progress]),
            steps = runSteps(row[WorkflowRuns.id].value, row[WorkflowRuns.resourceId].toString()),
            errorMessage = row[WorkflowRuns.errorMessage],
            temporalWorkflowId = row[WorkflowRuns.temporalWorkflowId],
            temporalRunId = row[WorkflowRuns.temporalRunId],
            createdAt = row[WorkflowRuns.createdAt].toString(),
            completedAt = row[WorkflowRuns.completedAt]?.toString(),
            failedAt = row[WorkflowRuns.failedAt]?.toString()
        )

    private fun runSteps(runId: Int, runResourceId: String): List<WorkflowRunStepResponse> =
        WorkflowRunSteps
            .selectAll()
            .where { WorkflowRunSteps.runId eq runId }
            .orderBy(WorkflowRunSteps.id to SortOrder.ASC)
            .map { row ->
                WorkflowRunStepResponse(
                    id = row[WorkflowRunSteps.resourceId].toString(),
                    runId = runResourceId,
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
            resourceId = row[WorkflowVersions.resourceId],
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
            .filter { reference -> WORKFLOW_ENGINE_CONFIG_PREFIXES.any { prefix -> reference.startsWith(prefix) } }
            .distinct()

    private fun alertScope(
        event: AlertLifecycleEvent,
        episode: AlertEpisodeDecision
    ): Map<String, JsonElement> {
        val baseScope =
            mapOf(
                ALERT_TITLE_REFERENCE to event.title,
                ALERT_DESCRIPTION_REFERENCE to event.description,
                ALERT_PRIORITY_REFERENCE to event.priority.wire,
                ALERT_STATUS_REFERENCE to event.status.name,
                ALERT_SOURCE_REFERENCE to event.source.name,
                ALERT_DEDUPLICATION_KEY_REFERENCE to event.deduplicationKey,
                ALERT_URL_REFERENCE to event.moneatUrl,
                ORGANIZATION_ID_REFERENCE to organizationResourceId(event.organizationId)
            ).typedWorkflowScope()
        return baseScope + episodeScopeFromDecision(episode) + alertMetadataScope(event.metadata)
    }

    private fun episodeScopeFromDecision(episode: AlertEpisodeDecision): Map<String, JsonElement> =
        mapOf(
            ALERT_EPISODE_ID_REFERENCE to episode.episode.resourceId.toString(),
            ALERT_EPISODE_KEY_REFERENCE to episode.episode.episodeKey,
            ALERT_EPISODE_SEQ_REFERENCE to episode.episode.episodeSeq.toString(),
            ALERT_NOTIFICATION_SEQUENCE_REFERENCE to episode.notificationSequence.toString(),
            ALERT_NOTIFICATION_KIND_REFERENCE to episode.notificationKind,
            ALERT_OPENED_AT_REFERENCE to episode.episode.openedAt.toString(),
            ALERT_LAST_SEEN_AT_REFERENCE to episode.episode.lastSeenAt.toString()
        ).typedWorkflowScope()

    private fun episodeScopeFromContext(
        episode: AlertEpisodeContext,
        notificationKind: String
    ): Map<String, JsonElement> =
        mapOf(
            ALERT_EPISODE_ID_REFERENCE to episode.resourceId.toString(),
            ALERT_EPISODE_KEY_REFERENCE to episode.episodeKey,
            ALERT_EPISODE_SEQ_REFERENCE to episode.episodeSeq.toString(),
            ALERT_NOTIFICATION_SEQUENCE_REFERENCE to episode.notificationCount.toString(),
            ALERT_NOTIFICATION_KIND_REFERENCE to notificationKind,
            ALERT_OPENED_AT_REFERENCE to episode.openedAt.toString(),
            ALERT_LAST_SEEN_AT_REFERENCE to episode.lastSeenAt.toString()
        ).typedWorkflowScope()

    private fun alertMetadataScope(metadata: Map<String, JsonElement>): Map<String, JsonElement> =
        metadata
            .mapNotNull { (reference, value) ->
                if (reference !in ALERT_METADATA_REFERENCES) return@mapNotNull null
                value.workflowScopeValue()?.let { reference to JsonPrimitive(it) }
            }.toMap()

    private fun incidentScope(
        organizationId: Int,
        status: String,
        title: String,
        severity: String,
        deduplicationKey: String,
        episode: AlertEpisodeContext
    ): Map<String, JsonElement> {
        val episodeResourceId = episode.resourceId.toString()
        val baseScope = mapOf(
            INCIDENT_ID_REFERENCE to episodeResourceId,
            INCIDENT_KIND_REFERENCE to "alert_episode",
            INCIDENT_TITLE_REFERENCE to title,
            INCIDENT_STATUS_REFERENCE to status,
            INCIDENT_SEVERITY_REFERENCE to severity,
            ALERT_DEDUPLICATION_KEY_REFERENCE to deduplicationKey,
            ORGANIZATION_ID_REFERENCE to organizationResourceId(organizationId)
        ).typedWorkflowScope()
        return baseScope + episodeScopeFromContext(episode, status)
    }

    private fun declaredIncidentScope(
        organizationId: Int,
        incidentId: Int,
        status: String,
        title: String,
        severity: IncidentSeverity
    ): Map<String, JsonElement> {
        val incidentResourceId = declaredIncidentResourceId(organizationId, incidentId)
        return mapOf(
            INCIDENT_ID_REFERENCE to incidentResourceId,
            INCIDENT_KIND_REFERENCE to "native_incident",
            INCIDENT_TITLE_REFERENCE to title,
            INCIDENT_STATUS_REFERENCE to status,
            INCIDENT_SEVERITY_REFERENCE to severity.wire,
            ORGANIZATION_ID_REFERENCE to organizationResourceId(organizationId)
        ).typedWorkflowScope()
    }

    private fun declaredIncidentRoleScope(change: DeclaredIncidentRoleChange): Map<String, JsonElement> {
        val incidentResourceId = declaredIncidentResourceId(change.organizationId, change.incidentId)
        return mapOf(
            INCIDENT_ID_REFERENCE to incidentResourceId,
            INCIDENT_KIND_REFERENCE to "native_incident",
            INCIDENT_TITLE_REFERENCE to change.title,
            INCIDENT_STATUS_REFERENCE to "role_changed",
            INCIDENT_SEVERITY_REFERENCE to change.severity?.wire.orEmpty(),
            INCIDENT_ROLE_REFERENCE to change.role,
            INCIDENT_ASSIGNEE_REFERENCE to change.assignee.orEmpty(),
            INCIDENT_ROLE_ACTION_REFERENCE to change.action,
            ORGANIZATION_ID_REFERENCE to organizationResourceId(change.organizationId),
        ).typedWorkflowScope()
    }

    private fun securitySignalScope(
        organizationId: Int,
        signal: SignalOutcome
    ): Map<String, JsonElement> =
        mapOf(
            SECURITY_RULE_ID_REFERENCE to signal.ruleId,
            SECURITY_RULE_NAME_REFERENCE to signal.ruleName,
            SECURITY_SEVERITY_REFERENCE to signal.severity.wire,
            SECURITY_RESOURCE_REFERENCE to signalResource(signal),
            ORGANIZATION_ID_REFERENCE to organizationResourceId(organizationId)
        ).typedWorkflowScope()

    private fun signalResource(signal: SignalOutcome): String =
        signal.entities["resource"]
            ?: signal.entities["process"]
            ?: signal.entities["host"]
            ?: ""

    private fun sourceSpecificAlertTrigger(event: AlertLifecycleEvent): String? =
        if (event.status == AlertStatus.RESOLVED) {
            specificResolvedAlertTrigger(event.source)
        } else {
            specificFiringAlertTrigger(event.source)
        }

    private fun specificFiringAlertTrigger(source: AlertSource): String? =
        when (source) {
            AlertSource.HOST_ALERT,
            AlertSource.HOST_DOWN,
            AlertSource.DASHBOARD_ALERT -> MONITOR_ALERTED_TRIGGER
            AlertSource.UPTIME_MONITOR -> UPTIME_DOWN_TRIGGER
            AlertSource.SYNTHETIC_TEST -> SYNTHETIC_FAILED_TRIGGER
            AlertSource.ERROR_ALERT -> null
        }

    private fun specificResolvedAlertTrigger(source: AlertSource): String? =
        when (source) {
            AlertSource.HOST_ALERT,
            AlertSource.HOST_DOWN,
            AlertSource.DASHBOARD_ALERT -> MONITOR_RECOVERED_TRIGGER
            AlertSource.UPTIME_MONITOR -> UPTIME_UP_TRIGGER
            AlertSource.SYNTHETIC_TEST -> SYNTHETIC_PASSED_TRIGGER
            AlertSource.ERROR_ALERT -> null
        }

    private fun JsonElement.workflowScopeValue(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.booleanOrNull?.toString() ?: primitive.contentOrNull
    }

    companion object {
        private const val ALERT_TRIGGERED_TRIGGER = "alert.triggered"
        private const val ALERT_RESOLVED_TRIGGER = "alert.resolved"
        private const val MONITOR_ALERTED_TRIGGER = "monitor.alerted"
        private const val MONITOR_RECOVERED_TRIGGER = "monitor.recovered"
        private const val UPTIME_DOWN_TRIGGER = "uptime.down"
        private const val UPTIME_UP_TRIGGER = "uptime.up"
        private const val SYNTHETIC_FAILED_TRIGGER = "synthetic.failed"
        private const val SYNTHETIC_PASSED_TRIGGER = "synthetic.passed"
        private const val INCIDENT_CREATED_TRIGGER = "incident.created"
        private const val INCIDENT_RESOLVED_TRIGGER = "incident.resolved"
        private const val INCIDENT_ROLE_CHANGED_TRIGGER = "incident.role_changed"
        private const val SECURITY_SIGNAL_TRIGGER = "security.signal"
        private const val API_TRIGGER = "api"
        private const val WEBHOOK_TRIGGER = "webhook"
        private const val STATUS_CANCELED = "canceled"
        private const val INCIDENT_STATUS_CREATED = "created"
        private const val INCIDENT_STATUS_RESOLVED = "resolved"
        private val TERMINAL_RUN_STATUSES = setOf("complete", "failed", STATUS_CANCELED)
        private val WORKFLOW_ENGINE_CONFIG_PREFIXES =
            listOf(
                "alert.",
                "organization.",
                "workflow.",
                "webhook.",
                "schedule.",
                "incident.",
                "security.",
                "oncall.",
                "steps."
            )
        private val DEFAULT_WORKFLOWS =
            listOf(
                DefaultWorkflowDefinition(
                    systemKey = "default_alert_notifications",
                    name = "Send alert notifications",
                    triggerName = ALERT_TRIGGERED_TRIGGER,
                    onceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_NOTIFICATION_SEQUENCE_REFERENCE),
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
                    onceForTemplate = listOf(ALERT_EPISODE_KEY_REFERENCE, ALERT_STATUS_REFERENCE),
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
    val resourceId: Uuid,
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

/** Trigger eligibility policy applied when starting a run. */
private data class TriggerGate(
    val requiredTriggerName: String? = null,
    val requirePublished: Boolean = false,
    val applyConditions: Boolean = false
)

private data class WorkflowReference(
    val id: Int,
    val resourceId: Uuid,
    val organizationId: Int,
    val triggerName: String
)

private data class WorkflowRunCancelCandidate(
    val status: String,
    val temporalWorkflowId: String,
    val resourceId: Uuid
)

private data class WorkflowRunStats(
    val lastRunAt: String?,
    val runCount: Long
)
