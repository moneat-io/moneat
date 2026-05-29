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

import com.moneat.workflows.engine.WorkflowConditionEvaluator
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphEdge
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.workflowArrayValue
import com.moneat.workflows.models.workflowStringValue
import com.moneat.workflows.models.workflowValue
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val STATUS_COMPLETE = "complete"
private const val STATUS_FAILED = "failed"
private const val STATUS_RUNNING = "running"
private const val STATUS_PENDING = "pending"
private const val ACTIVITY_TIMEOUT_SECONDS = 30L
private const val DEFAULT_MAX_ITEMS = 100
const val MAX_WHILE_ITERATIONS = 2_000

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

    @ActivityMethod
    fun markStepRunning(input: PersistRunStepInput)

    @ActivityMethod
    fun markStepComplete(input: PersistRunStepInput)

    @ActivityMethod
    fun markStepFailed(input: PersistRunStepInput)
}

class WorkflowInterpreterWorkflowImpl : WorkflowInterpreterWorkflow {
    private val activityOptions =
        ActivityOptions
            .newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(ACTIVITY_TIMEOUT_SECONDS))
            .build()
    private val runs = Workflow.newActivityStub(PersistRunActivity::class.java, activityOptions)

    override fun run(input: WorkflowInterpreterInput): WorkflowInterpreterResult {
        val snapshot = runs.loadRun(LoadRunInput(runId = input.runId))
            ?: return WorkflowInterpreterResult(status = STATUS_COMPLETE)
        val graph = snapshot.graph.toWorkflowGraphConfig().normalized(input.triggerName, snapshot.steps)
        val initialProgress = snapshot.progress.toWorkflowProgress().ifEmpty { graph.initialProgress() }
        runs.markRunning(PersistRunProgressInput(input.runId, STATUS_RUNNING, initialProgress.toRuntimeProgress()))

        val context = GraphExecutionContext(input, snapshot, graph, initialProgress)
        val triggerNode = graph.nodes.firstOrNull { node -> node.type == LinearGraphAdapter.NODE_TYPE_TRIGGER }
        if (triggerNode != null) {
            context.executeFrom(triggerNode.id)
        }

        return context.completeRun()
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
                    step = node.displayName(),
                    status = STATUS_PENDING,
                    nodeId = node.id,
                    type = node.type
                )
            }

    private fun WorkflowGraphNode.displayName(): String =
        action ?: kind ?: trigger ?: id

    private inner class GraphExecutionContext(
        private val input: WorkflowInterpreterInput,
        private val snapshot: WorkflowRunExecutionSnapshot,
        private val graph: WorkflowGraphConfig,
        initialProgress: List<WorkflowRunStepProgress>
    ) {
        private val nodesById = graph.nodes.associateBy { node -> node.id }
        private val edgesBySource = graph.edges.groupBy { edge -> edge.from }
        private val scope = snapshot.scope.toWorkflowValues().toMutableMap()
        private var progress = initialProgress
        private var errorMessage: String? = null

        fun executeFrom(nodeId: String) {
            val node = nodesById[nodeId] ?: return
            when (node.type) {
                LinearGraphAdapter.NODE_TYPE_TRIGGER -> follow(node)
                LinearGraphAdapter.NODE_TYPE_CONDITION -> executeCondition(node)
                LinearGraphAdapter.NODE_TYPE_ACTION -> executeAction(node)
                LinearGraphAdapter.NODE_TYPE_CONTROL -> executeControl(node)
            }
        }

        fun completeRun(): WorkflowInterpreterResult {
            val failedStep = progress.firstOrNull { step -> step.status == STATUS_FAILED }
            val failure = errorMessage ?: failedStep?.errorMessage
            return if (failure == null) {
                val runtimeProgress = progress.toRuntimeProgress()
                runs.markComplete(PersistRunProgressInput(input.runId, STATUS_COMPLETE, runtimeProgress))
                WorkflowInterpreterResult(status = STATUS_COMPLETE, progress = runtimeProgress)
            } else {
                val runtimeProgress = progress.toRuntimeProgress()
                runs.markFailed(PersistRunFailureInput(input.runId, runtimeProgress, failure))
                WorkflowInterpreterResult(status = STATUS_FAILED, progress = runtimeProgress, errorMessage = failure)
            }
        }

        private fun executeCondition(node: WorkflowGraphNode) {
            markNodeRunning(node, emptyMap())
            val branch =
                if (node.kind == LinearGraphAdapter.CONDITION_KIND_SWITCH) {
                    switchBranch(node)
                } else if (WorkflowConditionEvaluator.matchesAll(input.triggerName, node.conditions, scope)) {
                    "true"
                } else {
                    "false"
                }
            markNodeComplete(node, mapOf("branch" to JsonPrimitive(branch)))
            follow(node, branch = branch)
        }

        private fun switchBranch(node: WorkflowGraphNode): String {
            val matchedCase =
                node.cases.firstOrNull { item ->
                    WorkflowConditionEvaluator.matchesAll(input.triggerName, item.conditions, scope)
                }
            return matchedCase?.name ?: "default"
        }

        private fun executeAction(node: WorkflowGraphNode) {
            val action = node.action ?: return
            val step = WorkflowStepConfig(action, node.params.mapValues { (_, value) -> value.workflowStringValue() })
            val actions = Workflow.newActivityStub(ExecuteActionActivity::class.java, activityOptionsFor(node))
            markNodeRunning(node, scope)
            val result =
                try {
                    actions.execute(ExecuteActionInput(snapshot.organizationId, step, scope.toRuntimeValues()))
                } catch (failure: ActivityFailure) {
                    handleActionFailure(node, failure.workflowStepMessage())
                    return
                } catch (failure: RuntimeException) {
                    handleActionFailure(node, failure.workflowStepMessage())
                    return
                }
            val output = result.output.toWorkflowValues()
            if (result.status == STATUS_COMPLETE) {
                mergeStepOutput(node, output)
                markNodeComplete(node, output, result.completedAt)
                follow(node)
                return
            }
            handleActionFailure(node, result.errorMessage ?: "Unknown workflow step error", result.completedAt)
        }

        private fun handleActionFailure(
            node: WorkflowGraphNode,
            message: String,
            completedAt: String = Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString()
        ) {
            markNodeFailed(node, message, completedAt)
            val errorEdge = edgesBySource[node.id].orEmpty().firstOrNull { edge -> edge.on == "error" }
            when {
                errorEdge != null -> executeFrom(errorEdge.to)
                node.continueOnError -> follow(node)
                else -> errorMessage = message
            }
        }

        private fun RuntimeException.workflowStepMessage(): String =
            cause?.message ?: message ?: "Unknown workflow step error"

        private fun executeControl(node: WorkflowGraphNode) {
            when (node.kind) {
                LinearGraphAdapter.CONTROL_KIND_SLEEP -> executeSleep(node)
                LinearGraphAdapter.CONTROL_KIND_WAIT_UNTIL -> executeWaitUntil(node)
                LinearGraphAdapter.CONTROL_KIND_FOR_EACH -> executeForEach(node)
                LinearGraphAdapter.CONTROL_KIND_WHILE -> executeWhile(node)
                else -> {
                    markNodeFailed(node, "Unsupported control node ${node.kind}")
                    errorMessage = "Unsupported control node ${node.kind}"
                }
            }
        }

        private fun executeSleep(node: WorkflowGraphNode) {
            val duration = Duration.parse(node.params["duration"]?.workflowStringValue() ?: "PT0S")
            markNodeRunning(node, emptyMap())
            Workflow.sleep(duration)
            markNodeComplete(node, mapOf("duration" to JsonPrimitive(duration.toString())))
            follow(node)
        }

        private fun executeWaitUntil(node: WorkflowGraphNode) {
            val timeout = Duration.parse(node.params["timeout"]?.workflowStringValue() ?: "PT1M")
            markNodeRunning(node, emptyMap())
            val matched = Workflow.await(timeout) {
                WorkflowConditionEvaluator.matchesAll(input.triggerName, node.conditions, scope)
            }
            val branch = if (matched) "true" else "timeout"
            markNodeComplete(node, mapOf("branch" to JsonPrimitive(branch)))
            follow(node, branch)
        }

        private fun executeForEach(node: WorkflowGraphNode) {
            val itemReference = node.params["items_reference"]?.workflowStringValue().orEmpty()
            val itemVariable = node.params["item_variable"]?.workflowStringValue()?.ifBlank { null } ?: "item"
            val maxItems = boundedIntParam(node, "max_items", DEFAULT_MAX_ITEMS)
            val items = scope.workflowValue(itemReference)?.workflowArrayValue().orEmpty().take(maxItems)
            markNodeRunning(node, mapOf("items" to JsonPrimitive(itemReference)))
            var completedItems = 0
            items.forEachIndexed { index, item ->
                scope[itemVariable] = item
                scope["$itemVariable.index"] = JsonPrimitive(index)
                follow(node, branch = "body")
                if (errorMessage != null) {
                    markNodeFailed(node, errorMessage ?: "For-each body failed")
                    return
                }
                completedItems += 1
            }
            markNodeComplete(node, mapOf("count" to JsonPrimitive(completedItems)))
            follow(node, branch = "done")
        }

        private fun executeWhile(node: WorkflowGraphNode) {
            val maxIterations = boundedIntParam(node, "max_iterations", MAX_WHILE_ITERATIONS)
            markNodeRunning(node, emptyMap())
            var iterations = 0
            while (iterations < maxIterations &&
                WorkflowConditionEvaluator.matchesAll(input.triggerName, node.conditions, scope)
            ) {
                iterations += 1
                follow(node, branch = "body")
                if (errorMessage != null) break
            }
            if (iterations >= maxIterations) {
                markNodeFailed(node, "While node ${node.id} reached the iteration cap")
                errorMessage = "While node ${node.id} reached the iteration cap"
                return
            }
            markNodeComplete(node, mapOf("iterations" to JsonPrimitive(iterations)))
            follow(node, branch = "done")
        }

        private fun follow(
            node: WorkflowGraphNode,
            branch: String? = null
        ) {
            if (errorMessage != null) return
            val nextEdges = edgesBySource[node.id].orEmpty().filter { edge -> edge.matches(branch) }
            for (edge in nextEdges) {
                if (errorMessage != null) return
                executeFrom(edge.to)
            }
        }

        private fun WorkflowGraphEdge.matches(branch: String?): Boolean {
            if (on == "error") return false
            if (branch == null) return this.branch == null
            return this.branch == branch
        }

        private fun activityOptionsFor(node: WorkflowGraphNode): ActivityOptions {
            val retry = node.retry ?: return activityOptions
            val builder =
                RetryOptions
                    .newBuilder()
                    .setMaximumAttempts(retry.maxAttempts)
                    .setInitialInterval(Duration.parse(retry.initialInterval))
                    .setBackoffCoefficient(retry.backoffCoefficient)
            retry.maximumInterval?.let { value -> builder.setMaximumInterval(Duration.parse(value)) }
            if (retry.nonRetryableErrorTypes.isNotEmpty()) {
                builder.setDoNotRetry(*retry.nonRetryableErrorTypes.toTypedArray())
            }
            return ActivityOptions
                .newBuilder(activityOptions)
                .setRetryOptions(builder.build())
                .build()
        }

        private fun mergeStepOutput(
            node: WorkflowGraphNode,
            output: Map<String, JsonElement>
        ) {
            output.forEach { (key, value) ->
                scope["steps.${node.id}.output.$key"] = value
            }
        }

        private fun markNodeRunning(
            node: WorkflowGraphNode,
            nodeInput: Map<String, JsonElement>
        ) {
            updateProgress(node, STATUS_RUNNING, null, emptyMap(), null)
            runs.markStepRunning(
                PersistRunStepInput(
                    input.runId,
                    node.id,
                    node.type,
                    STATUS_RUNNING,
                    nodeInput.toRuntimeValues()
                )
            )
            runs.markRunning(PersistRunProgressInput(input.runId, STATUS_RUNNING, progress.toRuntimeProgress()))
        }

        private fun markNodeComplete(
            node: WorkflowGraphNode,
            output: Map<String, JsonElement>,
            completedAt: String = Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString()
        ) {
            updateProgress(node, STATUS_COMPLETE, completedAt, output, null)
            runs.markStepComplete(
                PersistRunStepInput(
                    runId = input.runId,
                    nodeId = node.id,
                    type = node.type,
                    status = STATUS_COMPLETE,
                    output = output.toRuntimeValues()
                )
            )
            runs.markRunning(PersistRunProgressInput(input.runId, STATUS_RUNNING, progress.toRuntimeProgress()))
        }

        private fun markNodeFailed(
            node: WorkflowGraphNode,
            message: String,
            completedAt: String = Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString()
        ) {
            updateProgress(node, STATUS_FAILED, completedAt, emptyMap(), message)
            runs.markStepFailed(
                PersistRunStepInput(
                    runId = input.runId,
                    nodeId = node.id,
                    type = node.type,
                    status = STATUS_FAILED,
                    errorMessage = message
                )
            )
            runs.markRunning(PersistRunProgressInput(input.runId, STATUS_RUNNING, progress.toRuntimeProgress()))
        }

        private fun updateProgress(
            node: WorkflowGraphNode,
            status: String,
            completedAt: String?,
            output: Map<String, JsonElement>,
            message: String?
        ) {
            progress = progress.map { item ->
                val matchesNode = item.nodeId == node.id ||
                    (item.nodeId.isNullOrBlank() && item.step == node.displayName())
                if (matchesNode) {
                    item.copy(status = status, completedAt = completedAt, output = output, errorMessage = message)
                } else {
                    item
                }
            }
        }

        private fun boundedIntParam(
            node: WorkflowGraphNode,
            paramName: String,
            defaultValue: Int
        ): Int =
            node.params[paramName]
                ?.workflowStringValue()
                ?.toIntOrNull()
                ?.coerceIn(1, MAX_WHILE_ITERATIONS)
                ?: defaultValue
    }
}
