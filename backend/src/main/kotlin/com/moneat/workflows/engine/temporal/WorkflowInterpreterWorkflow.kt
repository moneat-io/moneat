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
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import io.temporal.workflow.SignalMethod
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

internal fun approvalSignalKey(nodeId: String, approvalId: Int): String = "$nodeId:$approvalId"

@WorkflowInterface
interface WorkflowInterpreterWorkflow {
    @WorkflowMethod
    fun run(input: WorkflowInterpreterInput): WorkflowInterpreterResult

    @SignalMethod
    fun approve(input: WorkflowApprovalSignal)
}

@ActivityInterface
fun interface ExecuteActionActivity {
    @ActivityMethod
    fun execute(input: ExecuteActionInput): ExecuteActionResult
}

@ActivityInterface
fun interface ExecuteEgressActionActivity {
    @ActivityMethod
    fun execute(input: ExecuteActionInput): ExecuteActionResult
}

@ActivityInterface
fun interface RequestApprovalActivity {
    @ActivityMethod
    fun request(input: WorkflowApprovalRequestInput): WorkflowApprovalRequestResult
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
    private val approvals = Workflow.newActivityStub(RequestApprovalActivity::class.java, activityOptions)
    private val approvalSignals = mutableMapOf<String, WorkflowApprovalSignal>()

    override fun approve(input: WorkflowApprovalSignal) {
        val approvalId = input.approvalId ?: return
        approvalSignals[approvalSignalKey(input.nodeId, approvalId)] = input
    }

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
            val failure = resolveFailure()
            val runtimeProgress = progress.toRuntimeProgress()
            return if (failure == null) {
                runs.markComplete(PersistRunProgressInput(input.runId, STATUS_COMPLETE, runtimeProgress))
                WorkflowInterpreterResult(status = STATUS_COMPLETE, progress = runtimeProgress)
            } else {
                runs.markFailed(PersistRunFailureInput(input.runId, runtimeProgress, failure))
                WorkflowInterpreterResult(status = STATUS_FAILED, progress = runtimeProgress, errorMessage = failure)
            }
        }

        // Extracted so the optional-failure resolution stays opaque to flow analysis:
        // a fully successful run legitimately resolves to null here.
        private fun resolveFailure(): String? =
            errorMessage ?: progress.firstOrNull { step -> step.status == STATUS_FAILED }?.errorMessage

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
            markNodeRunning(node, scope)
            val result =
                try {
                    executeActionActivity(
                        node = node,
                        action = action,
                        input = ExecuteActionInput(
                            organizationId = snapshot.organizationId,
                            step = step,
                            scope = scope.toRuntimeValues(),
                            runId = input.runId,
                            nodeId = node.id,
                        ),
                    )
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
                LinearGraphAdapter.CONTROL_KIND_APPROVAL -> executeApproval(node)
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
                val failure = errorMessage
                if (failure != null) {
                    markNodeFailed(node, failure)
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

        private fun executeApproval(node: WorkflowGraphNode) {
            val timeout = Duration.parse(node.params["timeout"]?.workflowStringValue() ?: "PT24H")
            val message = node.params["message"]?.workflowStringValue() ?: "Workflow approval requested"
            markNodeRunning(node, mapOf("message" to JsonPrimitive(message)))
            val request =
                WorkflowApprovalRequestInput(
                    runId = input.runId,
                    workflowId = input.workflowId,
                    organizationId = input.organizationId,
                    nodeId = node.id,
                    message = message,
                    approverRole = node.params["approver_role"]?.workflowStringValue(),
                    timeout = timeout.toString()
                )
            val result = approvals.request(request)
            if (result.status == STATUS_FAILED) {
                val messageText = result.errorMessage ?: "Workflow approval request failed"
                markNodeFailed(node, messageText)
                errorMessage = messageText
                return
            }
            val approvalId =
                result.approvalId ?: run {
                    val messageText = "Workflow approval request did not return an approval id"
                    markNodeFailed(node, messageText)
                    errorMessage = messageText
                    return
                }
            val signalKey = approvalSignalKey(node.id, approvalId)
            val signaled = Workflow.await(timeout) { approvalSignals.containsKey(signalKey) }
            val signal = approvalSignals.remove(signalKey)
            val branch =
                when {
                    !signaled -> "timeout"
                    signal?.approved == true -> "approved"
                    else -> "rejected"
                }
            markNodeComplete(
                node,
                mapOf(
                    "approval_id" to JsonPrimitive(approvalId),
                    "branch" to JsonPrimitive(branch),
                    "comment" to JsonPrimitive(signal?.comment.orEmpty())
                )
            )
            follow(node, branch)
        }

        private fun follow(
            node: WorkflowGraphNode,
            branch: String? = null
        ) {
            if (errorMessage != null) return
            val nextEdges = edgesBySource[node.id].orEmpty().filter { edge -> edge.matches(branch) }
            if (nextEdges.canRunConcurrently()) {
                Promise.allOf(nextEdges.map { edge -> Async.procedure { executeFrom(edge.to) } }).get()
                return
            }
            for (edge in nextEdges) {
                if (errorMessage != null) return
                executeFrom(edge.to)
            }
        }

        private fun List<WorkflowGraphEdge>.canRunConcurrently(): Boolean =
            size > 1 &&
                all { edge ->
                    nodesById[edge.to]?.let { target ->
                        target.type == LinearGraphAdapter.NODE_TYPE_ACTION && target.continueOnError
                    } == true
                }

        private fun WorkflowGraphEdge.matches(branch: String?): Boolean {
            if (on == "error") return false
            if (branch == null) return this.branch == null
            return this.branch == branch
        }

        private fun executeActionActivity(
            node: WorkflowGraphNode,
            action: String,
            input: ExecuteActionInput
        ): ExecuteActionResult =
            if (action in WORKFLOW_EGRESS_ACTIONS) {
                Workflow
                    .newActivityStub(ExecuteEgressActionActivity::class.java, egressActivityOptionsFor(node))
                    .execute(input)
            } else {
                Workflow
                    .newActivityStub(ExecuteActionActivity::class.java, activityOptionsFor(node))
                    .execute(input)
            }

        private fun egressActivityOptionsFor(node: WorkflowGraphNode): ActivityOptions =
            ActivityOptions
                .newBuilder(activityOptionsFor(node))
                .setTaskQueue(WORKFLOW_EGRESS_TASK_QUEUE)
                .build()

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
