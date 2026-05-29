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
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

private const val STATUS_COMPLETE = "complete"
private const val STATUS_FAILED = "failed"
private const val STATUS_RUNNING = "running"
private const val STATUS_PENDING = "pending"
private const val ACTIVITY_TIMEOUT_SECONDS = 30L

@WorkflowInterface
interface WorkflowInterpreterWorkflow {
    @WorkflowMethod
    fun run(input: WorkflowInterpreterInput): WorkflowInterpreterResult
}

@ActivityInterface
interface ExecuteActionActivity {
    @ActivityMethod
    fun execute(input: ExecuteActionInput): ExecuteActionResult
}

@ActivityInterface
interface PersistRunActivity {
    @ActivityMethod
    fun loadRun(input: LoadRunInput): WorkflowRunExecutionSnapshot?

    @ActivityMethod
    fun markRunning(input: PersistRunProgressInput)

    @ActivityMethod
    fun markComplete(input: PersistRunProgressInput)

    @ActivityMethod
    fun markFailed(input: PersistRunFailureInput)
}

class WorkflowInterpreterWorkflowImpl : WorkflowInterpreterWorkflow {
    private val activityOptions =
        ActivityOptions
            .newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(ACTIVITY_TIMEOUT_SECONDS))
            .build()
    private val actions = Workflow.newActivityStub(ExecuteActionActivity::class.java, activityOptions)
    private val runs = Workflow.newActivityStub(PersistRunActivity::class.java, activityOptions)

    override fun run(input: WorkflowInterpreterInput): WorkflowInterpreterResult {
        val snapshot = runs.loadRun(LoadRunInput(runId = input.runId))
            ?: return WorkflowInterpreterResult(status = STATUS_COMPLETE)

        val initialProgress = snapshot.progress.ifEmpty {
            LinearGraphAdapter.fromSteps(snapshot.steps)
                .map { node -> WorkflowRunStepProgress(step = node.step.name, status = STATUS_PENDING) }
        }
        runs.markRunning(PersistRunProgressInput(input.runId, STATUS_RUNNING, initialProgress))

        val promises = startActionPromises(snapshot, initialProgress)
        Promise.allOf(promises).get()

        val progress = progressFromResults(initialProgress, promises)
        val failedStep = progress.firstOrNull { step -> step.status == STATUS_FAILED }
        return if (failedStep == null) {
            runs.markComplete(PersistRunProgressInput(input.runId, STATUS_COMPLETE, progress))
            WorkflowInterpreterResult(status = STATUS_COMPLETE, progress = progress)
        } else {
            val message = failedStep.errorMessage ?: "Unknown workflow step error"
            runs.markFailed(PersistRunFailureInput(input.runId, progress, message))
            WorkflowInterpreterResult(status = STATUS_FAILED, progress = progress, errorMessage = message)
        }
    }

    private fun startActionPromises(
        snapshot: WorkflowRunExecutionSnapshot,
        initialProgress: List<WorkflowRunStepProgress>
    ): List<Promise<ActionProgressResult>> =
        LinearGraphAdapter.fromSteps(snapshot.steps).mapIndexed { index, node ->
            val existing = initialProgress.getOrNull(index)
            if (existing?.status == STATUS_COMPLETE) {
                Async.function { ActionProgressResult(index, existing) }
            } else {
                Async.function { executeAction(index, snapshot, node.step) }
            }
        }

    private fun executeAction(
        index: Int,
        snapshot: WorkflowRunExecutionSnapshot,
        step: com.moneat.workflows.models.WorkflowStepConfig
    ): ActionProgressResult {
        val result =
            actions.execute(
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
                completedAt = result.completedAt,
                errorMessage = result.errorMessage
            )
        return ActionProgressResult(index, progress)
    }

    private fun progressFromResults(
        initialProgress: List<WorkflowRunStepProgress>,
        promises: List<Promise<ActionProgressResult>>
    ): List<WorkflowRunStepProgress> {
        val replacements = promises.map { promise -> promise.get() }.associateBy { result -> result.index }
        return initialProgress.mapIndexed { index, item -> replacements[index]?.progress ?: item }
    }

    private data class ActionProgressResult(
        val index: Int,
        val progress: WorkflowRunStepProgress
    )
}
