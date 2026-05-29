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

import com.moneat.workflows.models.WorkflowRunStepProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val STATUS_COMPLETE = "complete"
private const val STATUS_FAILED = "failed"
private const val STATUS_PENDING = "pending"
private const val STATUS_RUNNING = "running"

class WorkflowDirectRunExecutor(
    private val actionActivity: ExecuteActionActivity,
    private val persistActivity: PersistRunActivity
) {
    suspend fun executeRun(runId: Int): WorkflowInterpreterResult {
        val snapshot = persistActivity.loadRun(LoadRunInput(runId)) ?: return WorkflowInterpreterResult(
            status = STATUS_COMPLETE
        )
        val initialProgress = snapshot.progress.ifEmpty {
            LinearGraphAdapter.fromSteps(snapshot.steps).map { node ->
                WorkflowRunStepProgress(step = node.step.name, status = STATUS_PENDING)
            }
        }
        persistActivity.markRunning(PersistRunProgressInput(runId, STATUS_RUNNING, initialProgress))
        val stepResults =
            coroutineScope {
                LinearGraphAdapter.fromSteps(snapshot.steps).mapIndexed { index, node ->
                    async(Dispatchers.IO) {
                        val existing = initialProgress.getOrNull(index)
                        if (existing?.status == STATUS_COMPLETE) return@async index to existing
                        val result =
                            actionActivity.execute(
                                ExecuteActionInput(
                                    organizationId = snapshot.organizationId,
                                    step = node.step,
                                    scope = snapshot.scope
                                )
                            )
                        index to WorkflowRunStepProgress(
                            step = node.step.name,
                            status = result.status,
                            completedAt = result.completedAt,
                            errorMessage = result.errorMessage
                        )
                    }
                }
            }.awaitAll()
        val progress = initialProgress.updateFrom(stepResults)
        val failedStep = progress.firstOrNull { step -> step.status == STATUS_FAILED }
        return if (failedStep == null) {
            persistActivity.markComplete(PersistRunProgressInput(runId, STATUS_COMPLETE, progress))
            WorkflowInterpreterResult(status = STATUS_COMPLETE, progress = progress)
        } else {
            val message = failedStep.errorMessage ?: "Unknown workflow step error"
            persistActivity.markFailed(PersistRunFailureInput(runId, progress, message))
            WorkflowInterpreterResult(status = STATUS_FAILED, progress = progress, errorMessage = message)
        }
    }

    private fun List<WorkflowRunStepProgress>.updateFrom(
        replacements: List<Pair<Int, WorkflowRunStepProgress>>
    ): List<WorkflowRunStepProgress> {
        val byIndex = replacements.toMap()
        return mapIndexed { index, item -> byIndex[index] ?: item }
    }
}
