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

import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.services.WorkflowActionExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
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
                    scope = input.scope
                )
            }.fold(
                onSuccess = {
                    ExecuteActionResult(
                        status = STATUS_COMPLETE,
                        completedAt = Clock.System.now().toString()
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

class PersistRunActivityImpl : PersistRunActivity {
    private val json = Json { ignoreUnknownKeys = true }

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
                steps = decodeSteps(versionRow),
                progress = decodeProgress(runRow[WorkflowRuns.progress]),
                scope = decodeScope(runRow[WorkflowRuns.scope])
            )
        }

    override fun markRunning(input: PersistRunProgressInput) {
        updateRunProgress(input.runId, STATUS_RUNNING, input.progress)
    }

    override fun markComplete(input: PersistRunProgressInput) {
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq input.runId }) {
                it[status] = STATUS_COMPLETE
                it[progress] = json.encodeToString(input.progress)
                it[completedAt] = Clock.System.now()
                it[errorMessage] = null
            }
        }
    }

    override fun markFailed(input: PersistRunFailureInput) {
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq input.runId }) {
                it[status] = STATUS_FAILED
                it[progress] = json.encodeToString(input.progress)
                it[errorMessage] = input.errorMessage
                it[failedAt] = Clock.System.now()
            }
        }
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
        progress: List<WorkflowRunStepProgress>
    ) {
        transaction {
            WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
                it[WorkflowRuns.status] = status
                it[WorkflowRuns.progress] = json.encodeToString(progress)
            }
        }
    }

    private fun decodeSteps(row: ResultRow): List<WorkflowStepConfig> =
        row[WorkflowVersions.steps].takeIf { it.isNotBlank() }?.let { decode(it) } ?: emptyList()

    private fun decodeProgress(raw: String): List<WorkflowRunStepProgress> =
        raw.takeIf { it.isNotBlank() }?.let { decode(it) } ?: emptyList()

    private fun decodeScope(raw: String): Map<String, String> =
        raw.takeIf { it.isNotBlank() }?.let { decode(it) } ?: emptyMap()

    private inline fun <reified T> decode(raw: String): T =
        json.decodeFromString(raw)
}
