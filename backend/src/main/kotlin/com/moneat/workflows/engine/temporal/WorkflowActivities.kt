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

package com.moneat.workflows.engine.temporal

import com.moneat.enterprise.FeatureRegistry
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.WorkflowApprovalBridge
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowRunSteps
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.workflowJson
import com.moneat.workflows.services.WorkflowActionExecutor
import com.moneat.workflows.services.WorkflowAudit
import com.moneat.workflows.services.WorkflowEgressActionExecutor
import com.moneat.workflows.services.WorkflowUsage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val STATUS_COMPLETE = "complete"
private const val STATUS_FAILED = "failed"
private const val STATUS_RUNNING = "running"

class ExecuteActionActivityImpl(
    private val actionExecutor: WorkflowActionExecutor
) : ExecuteActionActivity {
    override fun execute(input: ExecuteActionInput): ExecuteActionResult =
        runBlocking {
            suspendRunCatching {
                actionExecutor.executeStep(
                    organizationId = input.organizationId,
                    step = input.step,
                    scope = input.scope.toWorkflowValues()
                )
            }.fold(
                onSuccess = {
                    ExecuteActionResult(
                        status = STATUS_COMPLETE,
                        completedAt = Clock.System.now().toString(),
                        output = it.toRuntimeValues()
                    )
                },
                onFailure = { error ->
                    ExecuteActionResult(
                        status = STATUS_FAILED,
                        completedAt = Clock.System.now().toString(),
                        errorMessage = error.message ?: "Unknown workflow step error"
                    )
                }
            )
        }
}

class ExecuteEgressActionActivityImpl(
    private val egressActionExecutor: WorkflowEgressActionExecutor
) : ExecuteEgressActionActivity {
    override fun execute(input: ExecuteActionInput): ExecuteActionResult =
        runBlocking {
            suspendRunCatching {
                egressActionExecutor.execute(
                    stepName = input.step.name,
                    params = input.step.params,
                    scope = input.scope
                )
            }.fold(
                onSuccess = {
                    ExecuteActionResult(
                        status = STATUS_COMPLETE,
                        completedAt = Clock.System.now().toString(),
                        output = it.toRuntimeValues()
                    )
                },
                onFailure = { error ->
                    ExecuteActionResult(
                        status = STATUS_FAILED,
                        completedAt = Clock.System.now().toString(),
                        errorMessage = error.message ?: "Unknown egress workflow step error"
                    )
                }
            )
        }
}

class RequestApprovalActivityImpl(
    private val bridgeProvider: () -> WorkflowApprovalBridge? = { FeatureRegistry.getWorkflowApprovalBridge() }
) : RequestApprovalActivity {
    override fun request(input: WorkflowApprovalRequestInput): WorkflowApprovalRequestResult =
        runBlocking {
            val bridge = bridgeProvider()
                ?: return@runBlocking WorkflowApprovalRequestResult(
                    status = STATUS_FAILED,
                    errorMessage = "Workflow approvals require the workflows_advanced Enterprise module"
                )
            suspendRunCatching {
                bridge.requestApproval(input)
            }.getOrElse { error ->
                WorkflowApprovalRequestResult(
                    status = STATUS_FAILED,
                    errorMessage = error.message ?: "Workflow approval request failed"
                )
            }
        }
}

class PersistRunActivityImpl : PersistRunActivity {
    private val json = workflowJson

    override fun loadRun(input: LoadRunInput): WorkflowRunExecutionSnapshot? =
        transaction {
            val runRow =
                WorkflowRuns
                    .selectAll()
                    .where { WorkflowRuns.id eq input.runId }
                    .firstOrNull() ?: return@transaction null
            if (runRow[WorkflowRuns.status] == STATUS_COMPLETE) return@transaction null
            val versionRow =
                WorkflowVersions
                    .selectAll()
                    .where { WorkflowVersions.id eq runRow[WorkflowRuns.workflowVersionId] }
                    .firstOrNull() ?: return@transaction null
            WorkflowRunExecutionSnapshot(
                runId = input.runId,
                organizationId = runRow[WorkflowRuns.organizationId],
                workflowVersionId = runRow[WorkflowRuns.workflowVersionId],
                triggerName = runRow[WorkflowRuns.triggerName],
                steps = decodeSteps(versionRow),
                graph = decodeGraph(versionRow).toRuntimeGraph(),
                progress = decodeProgress(runRow[WorkflowRuns.progress]).toRuntimeProgress(),
                scope = decodeScope(runRow[WorkflowRuns.scope]).toRuntimeValues()
            )
        }

    override fun markRunning(input: PersistRunProgressInput) {
        updateRunProgress(input.runId, STATUS_RUNNING, input.progress)
    }

    override fun markComplete(input: PersistRunProgressInput) {
        val transition =
            transaction {
                val runRow =
                    WorkflowRuns
                        .selectAll()
                        .where { WorkflowRuns.id eq input.runId }
                        .firstOrNull() ?: return@transaction null
                if (runRow[WorkflowRuns.status] == STATUS_COMPLETE) return@transaction null
                val completedAt = Clock.System.now()
                WorkflowRuns.update({ WorkflowRuns.id eq input.runId }) {
                    it[status] = STATUS_COMPLETE
                    it[progress] = json.encodeToString(input.progress.toWorkflowProgress())
                    it[WorkflowRuns.completedAt] = completedAt
                    it[errorMessage] = null
                    it[failedAt] = null
                }
                RunTerminalTransition(
                    organizationId = runRow[WorkflowRuns.organizationId],
                    workflowId = runRow[WorkflowRuns.workflowId],
                    triggerName = runRow[WorkflowRuns.triggerName],
                    createdAt = runRow[WorkflowRuns.createdAt],
                    terminalAt = completedAt
                )
            } ?: return
        WorkflowUsage.recordCompleted(input.runId)
        WorkflowAudit.record(
            organizationId = transition.organizationId,
            action = WorkflowAudit.ACTION_RUN_COMPLETED,
            workflowId = transition.workflowId,
            runId = input.runId
        )
        OperationalMetrics.recordWorkflowExecutionCompleted(
            transition.triggerName,
            transition.durationSeconds()
        )
    }

    override fun markFailed(input: PersistRunFailureInput) {
        val transition =
            transaction {
                val runRow =
                    WorkflowRuns
                        .selectAll()
                        .where { WorkflowRuns.id eq input.runId }
                        .firstOrNull() ?: return@transaction null
                if (runRow[WorkflowRuns.status] == STATUS_FAILED) return@transaction null
                val failedAt = Clock.System.now()
                WorkflowRuns.update({ WorkflowRuns.id eq input.runId }) {
                    it[status] = STATUS_FAILED
                    it[progress] = json.encodeToString(input.progress.toWorkflowProgress())
                    it[errorMessage] = input.errorMessage
                    it[WorkflowRuns.failedAt] = failedAt
                }
                RunTerminalTransition(
                    organizationId = runRow[WorkflowRuns.organizationId],
                    workflowId = runRow[WorkflowRuns.workflowId],
                    triggerName = runRow[WorkflowRuns.triggerName],
                    createdAt = runRow[WorkflowRuns.createdAt],
                    terminalAt = failedAt
                )
            } ?: return
        WorkflowAudit.record(
            organizationId = transition.organizationId,
            action = WorkflowAudit.ACTION_RUN_FAILED,
            workflowId = transition.workflowId,
            runId = input.runId
        )
        OperationalMetrics.recordWorkflowExecutionFailed(transition.triggerName)
    }

    override fun markStepRunning(input: PersistRunStepInput) {
        upsertStep(input.copy(status = STATUS_RUNNING))
    }

    override fun markStepComplete(input: PersistRunStepInput) {
        upsertStep(input.copy(status = STATUS_COMPLETE))
    }

    override fun markStepFailed(input: PersistRunStepInput) {
        upsertStep(input.copy(status = STATUS_FAILED))
    }

    fun updateTemporalRunId(
        runId: Int,
        temporalRunId: String?
    ) {
        if (temporalRunId.isNullOrBlank()) return
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
                it[WorkflowRuns.temporalRunId] = temporalRunId
            }
        }
    }

    private fun updateRunProgress(
        runId: Int,
        status: String,
        progress: List<RuntimeWorkflowRunStepProgress>
    ) {
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
                it[WorkflowRuns.status] = status
                it[WorkflowRuns.progress] = json.encodeToString(progress.toWorkflowProgress())
                it[WorkflowRuns.errorMessage] = null
                it[WorkflowRuns.failedAt] = null
            }
        }
    }

    private fun decodeSteps(row: ResultRow): List<WorkflowStepConfig> =
        row[WorkflowVersions.steps].takeIf { it.isNotBlank() }?.let { decode(it) } ?: emptyList()

    private fun decodeGraph(row: ResultRow): WorkflowGraphConfig =
        row[WorkflowVersions.graph].takeIf { it.isNotBlank() }?.let { decode(it) } ?: WorkflowGraphConfig()

    private fun decodeProgress(raw: String): List<WorkflowRunStepProgress> =
        raw.takeIf { it.isNotBlank() }?.let { decode(it) } ?: emptyList()

    private fun decodeScope(raw: String): Map<String, JsonElement> =
        raw.takeIf { it.isNotBlank() }?.let { decode(it) } ?: emptyMap()

    private fun upsertStep(input: PersistRunStepInput) {
        transaction {
            val existing =
                WorkflowRunSteps
                    .selectAll()
                    .where {
                        (WorkflowRunSteps.runId eq input.runId) and
                            (WorkflowRunSteps.nodeId eq input.nodeId) and
                            (WorkflowRunSteps.attempt eq input.attempt)
                    }.firstOrNull()
            val now = Clock.System.now()
            if (existing == null) {
                WorkflowRunSteps.insert {
                    it[runId] = input.runId
                    it[nodeId] = input.nodeId
                    it[type] = input.type
                    it[status] = input.status
                    it[startedAt] = now
                    it[completedAt] = input.completedAtForStatus(now)
                    it[WorkflowRunSteps.input] = json.encodeToString(input.input.toWorkflowValues())
                    it[output] = json.encodeToString(input.output.toWorkflowValues())
                    it[errorMessage] = input.errorMessage
                    it[attempt] = input.attempt
                }
            } else {
                WorkflowRunSteps.update({ WorkflowRunSteps.id eq existing[WorkflowRunSteps.id] }) {
                    it[status] = input.status
                    it[completedAt] = input.completedAtForStatus(now)
                    it[output] = json.encodeToString(input.output.toWorkflowValues())
                    it[errorMessage] = input.errorMessage
                }
            }
        }
    }

    private fun PersistRunStepInput.completedAtForStatus(now: kotlin.time.Instant): kotlin.time.Instant? =
        if (status == STATUS_COMPLETE || status == STATUS_FAILED) now else null

    private inline fun <reified T> decode(raw: String): T =
        json.decodeFromString(raw)
}

private data class RunTerminalTransition(
    val organizationId: Int,
    val workflowId: Int,
    val triggerName: String,
    val createdAt: kotlin.time.Instant,
    val terminalAt: kotlin.time.Instant
) {
    fun durationSeconds(): Double =
        (terminalAt - createdAt).inWholeMilliseconds.toDouble() / MILLIS_PER_SECOND
}

private const val MILLIS_PER_SECOND = 1_000.0
