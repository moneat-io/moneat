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

import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.workflowStringValue

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
        val graph = snapshot.graph.toWorkflowGraphConfig().normalized(snapshot.triggerName, snapshot.steps)
        val initialProgress = snapshot.progress.toWorkflowProgress().ifEmpty { graph.initialProgress() }
        persistActivity.markRunning(PersistRunProgressInput(runId, STATUS_RUNNING, initialProgress.toRuntimeProgress()))
        val progress = executeActions(runId, snapshot, graph, initialProgress)
        val failedStep = progress.firstOrNull { step -> step.status == STATUS_FAILED }
        return if (failedStep == null) {
            val runtimeProgress = progress.toRuntimeProgress()
            persistActivity.markComplete(PersistRunProgressInput(runId, STATUS_COMPLETE, runtimeProgress))
            WorkflowInterpreterResult(status = STATUS_COMPLETE, progress = runtimeProgress)
        } else {
            val message = failedStep.errorMessage ?: "Unknown workflow step error"
            val runtimeProgress = progress.toRuntimeProgress()
            persistActivity.markFailed(PersistRunFailureInput(runId, runtimeProgress, message))
            WorkflowInterpreterResult(status = STATUS_FAILED, progress = runtimeProgress, errorMessage = message)
        }
    }

    private fun executeActions(
        runId: Int,
        snapshot: WorkflowRunExecutionSnapshot,
        graph: WorkflowGraphConfig,
        initialProgress: List<WorkflowRunStepProgress>
    ): List<WorkflowRunStepProgress> {
        var progress = initialProgress.map { item ->
            if (item.type == LinearGraphAdapter.NODE_TYPE_ACTION) item else item.copy(status = STATUS_COMPLETE)
        }
        graph.nodes
            .filter { node -> node.type == LinearGraphAdapter.NODE_TYPE_ACTION }
            .forEach { node ->
                val result = executeActionNode(runId, snapshot, node)
                progress = progress.map { item -> if (item.nodeId == node.id) result else item }
            }
        return progress
    }

    private fun executeActionNode(
        runId: Int,
        snapshot: WorkflowRunExecutionSnapshot,
        node: WorkflowGraphNode
    ): WorkflowRunStepProgress {
        val step = WorkflowStepConfig(
            name = node.action.orEmpty(),
            params = node.params.mapValues { (_, value) -> value.workflowStringValue() }
        )
        persistActivity.markStepRunning(
            PersistRunStepInput(runId, node.id, node.type, STATUS_RUNNING, snapshot.scope)
        )
        val result =
            actionActivity.execute(
                ExecuteActionInput(
                    organizationId = snapshot.organizationId,
                    step = step,
                    scope = snapshot.scope
                )
            )
        val progress =
            WorkflowRunStepProgress(
                step = step.name,
                status = result.status,
                nodeId = node.id,
                type = node.type,
                completedAt = result.completedAt,
                output = result.output.toWorkflowValues(),
                errorMessage = result.errorMessage
            )
        persistStep(runId, node, result)
        return progress
    }

    private fun persistStep(
        runId: Int,
        node: WorkflowGraphNode,
        result: ExecuteActionResult
    ) {
        val input = emptyMap<String, String>()
        val stepInput =
            PersistRunStepInput(
                runId = runId,
                nodeId = node.id,
                type = node.type,
                status = result.status,
                input = input,
                output = result.output,
                errorMessage = result.errorMessage
            )
        if (result.status == STATUS_FAILED) {
            persistActivity.markStepFailed(stepInput)
        } else {
            persistActivity.markStepComplete(stepInput)
        }
    }

    private fun WorkflowGraphConfig.normalized(
        triggerName: String,
        steps: List<WorkflowStepConfig>
    ): WorkflowGraphConfig =
        if (nodes.isEmpty()) {
            LinearGraphAdapter.graphFromLegacy(triggerName, emptyList(), steps)
        } else {
            this
        }

    private fun WorkflowGraphConfig.initialProgress(): List<WorkflowRunStepProgress> =
        nodes
            .filter { node -> node.type != LinearGraphAdapter.NODE_TYPE_TRIGGER }
            .map { node ->
                WorkflowRunStepProgress(
                    step = node.action ?: node.kind ?: node.id,
                    status = STATUS_PENDING,
                    nodeId = node.id,
                    type = node.type
                )
            }
}
