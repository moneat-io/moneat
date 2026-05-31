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

import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphEdge
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowRetryConfig
import com.moneat.workflows.models.WorkflowStepConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ──── Test Constants ────

private const val STATUS_COMPLETE = "complete"
private const val STATUS_FAILED = "failed"
private const val STATUS_PENDING = "pending"
private const val STATUS_RUNNING = "running"
private const val TEST_COMPLETED_AT = "2026-05-29T00:00:00Z"
private const val TEST_PAYLOAD_KEY = "workflow-test-payload-key-32-bytes"
private const val TEST_RUN_ID = 7
private const val TEST_WORKFLOW_ID = 17
private const val TEST_WORKFLOW_VERSION_ID = 11
private const val TEST_ORGANIZATION_ID = 13

// ──── Workflow Tests ────

class WorkflowInterpreterWorkflowTest {

    @Test
    fun `interpreter completes flat workflow and persists progress projection`() {
        val actionActivity = RecordingExecuteActionActivity()
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    steps = listOf(
                        WorkflowStepConfig("notification.email"),
                        WorkflowStepConfig("notification.slack")
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_COMPLETE, result.status)
            assertEquals(STATUS_RUNNING, persistActivity.transitions.first())
            assertEquals(STATUS_COMPLETE, persistActivity.transitions.last())
            assertEquals(
                listOf("notification.email", "notification.slack"),
                result.progress.map { step -> step.step }
            )
            assertTrue(result.progress.all { step -> step.status == STATUS_COMPLETE })
            assertEquals(
                listOf("notification.email", "notification.slack"),
                actionActivity.executedSteps.sorted()
            )
        }
    }

    @Test
    fun `interpreter fails run when any flat workflow step fails`() {
        val actionActivity = RecordingExecuteActionActivity(failedSteps = setOf("notification.discord"))
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    steps = listOf(
                        WorkflowStepConfig("notification.email"),
                        WorkflowStepConfig("notification.discord")
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_FAILED, result.status)
            assertEquals(STATUS_RUNNING, persistActivity.transitions.first())
            assertEquals(STATUS_FAILED, persistActivity.transitions.last())
            assertEquals("notification.discord failed", result.errorMessage)
            assertEquals(STATUS_FAILED, result.progress.last().status)
            assertEquals("notification.discord failed", result.progress.last().errorMessage)
        }
    }

    @Test
    fun `interpreter runs every legacy step independently when an earlier step fails`() {
        val actionActivity = RecordingExecuteActionActivity(failedSteps = setOf("notification.email"))
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    steps = listOf(
                        WorkflowStepConfig("notification.email"),
                        WorkflowStepConfig("notification.slack")
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            // Legacy steps are independent: the failed step fails the run, but its sibling still runs.
            assertEquals(STATUS_FAILED, result.status)
            assertEquals(
                listOf("notification.email", "notification.slack"),
                actionActivity.executedSteps.sorted()
            )
            assertEquals("notification.email failed", result.errorMessage)
        }
    }

    @Test
    fun `interpreter runs independent legacy steps concurrently`() {
        val actionActivity = OverlapRecordingExecuteActionActivity()
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    steps = listOf(
                        WorkflowStepConfig("notification.email"),
                        WorkflowStepConfig("notification.slack")
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_COMPLETE, result.status)
            assertTrue(actionActivity.maxConcurrent.get() > 1)
        }
    }

    @Test
    fun `interpreter follows condition branch in graph workflow`() {
        val actionActivity = RecordingExecuteActionActivity()
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    graph = WorkflowGraphConfig(
                        nodes = listOf(
                            WorkflowGraphNode("trigger", "trigger", trigger = "alert.triggered"),
                            WorkflowGraphNode(
                                id = "severity-check",
                                type = "condition",
                                kind = "if",
                                conditions = listOf(
                                    WorkflowConditionConfig(
                                        reference = "alert.severity",
                                        operation = "eq",
                                        value = "critical"
                                    )
                                )
                            ),
                            WorkflowGraphNode(
                                id = "email",
                                type = "action",
                                action = "notification.email"
                            )
                        ),
                        edges = listOf(
                            WorkflowGraphEdge("trigger", "severity-check"),
                            WorkflowGraphEdge("severity-check", "email", branch = "true")
                        )
                    ),
                    scope = mapOf("alert.severity" to JsonPrimitive("critical"))
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_COMPLETE, result.status)
            assertEquals(listOf("notification.email"), actionActivity.executedSteps)
            assertEquals(listOf("severity-check", "email"), result.progress.map { step -> step.nodeId })
        }
    }

    @Test
    fun `interpreter persists activity exceptions as failed nodes`() {
        val actionActivity = RecordingExecuteActionActivity(throwingSteps = setOf("notification.email"))
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    graph = WorkflowGraphConfig(
                        nodes = listOf(
                            WorkflowGraphNode("trigger", "trigger", trigger = "alert.triggered"),
                            WorkflowGraphNode(
                                id = "email",
                                type = "action",
                                action = "notification.email",
                                retry = WorkflowRetryConfig(maxAttempts = 1)
                            )
                        ),
                        edges = listOf(WorkflowGraphEdge("trigger", "email"))
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_FAILED, result.status)
            assertEquals(STATUS_FAILED, result.progress.single().status)
            assertTrue(result.progress.single().errorMessage?.contains("notification.email exploded") == true)
        }
    }

    @Test
    fun `interpreter stops fan out after a branch failure`() {
        val actionActivity = RecordingExecuteActionActivity(failedSteps = setOf("notification.email"))
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    graph = WorkflowGraphConfig(
                        nodes = listOf(
                            WorkflowGraphNode("trigger", "trigger", trigger = "alert.triggered"),
                            WorkflowGraphNode("email", "action", action = "notification.email"),
                            WorkflowGraphNode("slack", "action", action = "notification.slack")
                        ),
                        edges = listOf(
                            WorkflowGraphEdge("trigger", "email"),
                            WorkflowGraphEdge("trigger", "slack")
                        )
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_FAILED, result.status)
            assertEquals(listOf("notification.email"), actionActivity.executedSteps)
            assertEquals(listOf(STATUS_FAILED, STATUS_PENDING), result.progress.map { step -> step.status })
        }
    }

    @Test
    fun `interpreter keeps repeated action progress scoped to node id`() {
        val actionActivity = RecordingExecuteActionActivity(failedSteps = setOf("notification.email"))
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    graph = WorkflowGraphConfig(
                        nodes = listOf(
                            WorkflowGraphNode("trigger", "trigger", trigger = "alert.triggered"),
                            WorkflowGraphNode("email-1", "action", action = "notification.email"),
                            WorkflowGraphNode("email-2", "action", action = "notification.email")
                        ),
                        edges = listOf(
                            WorkflowGraphEdge("trigger", "email-1"),
                            WorkflowGraphEdge("email-1", "email-2")
                        )
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_FAILED, result.status)
            assertEquals(listOf("email-1", "email-2"), result.progress.map { step -> step.nodeId })
            assertEquals(listOf(STATUS_FAILED, STATUS_PENDING), result.progress.map { step -> step.status })
        }
    }

    @Test
    fun `interpreter marks for each failed when the body fails`() {
        val actionActivity = RecordingExecuteActionActivity(failedSteps = setOf("notification.email"))
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    graph = WorkflowGraphConfig(
                        nodes = listOf(
                            WorkflowGraphNode("trigger", "trigger", trigger = "alert.triggered"),
                            WorkflowGraphNode(
                                id = "loop",
                                type = "control",
                                kind = "for_each",
                                params = mapOf(
                                    "items_reference" to JsonPrimitive("alert.items"),
                                    "item_variable" to JsonPrimitive("item"),
                                    "max_items" to JsonPrimitive("3")
                                )
                            ),
                            WorkflowGraphNode("email", "action", action = "notification.email")
                        ),
                        edges = listOf(
                            WorkflowGraphEdge("trigger", "loop"),
                            WorkflowGraphEdge("loop", "email", branch = "body")
                        )
                    ),
                    scope = mapOf(
                        "alert.items" to JsonArray(listOf(JsonPrimitive("first"), JsonPrimitive("second")))
                    )
                )
            )

        runWorkflow(actionActivity, persistActivity).use { harness ->
            val result = harness.workflow.run(input())

            assertEquals(STATUS_FAILED, result.status)
            assertEquals(listOf("notification.email"), actionActivity.executedSteps)
            assertEquals(STATUS_FAILED, result.progress.first { step -> step.nodeId == "loop" }.status)
        }
    }

    @Test
    fun `interpreter consumes only the matching approval signal`() {
        val actionActivity = RecordingExecuteActionActivity()
        val persistActivity =
            RecordingPersistRunActivity(
                snapshot = snapshot(
                    graph = WorkflowGraphConfig(
                        nodes = listOf(
                            WorkflowGraphNode("trigger", "trigger", trigger = "alert.triggered"),
                            WorkflowGraphNode(
                                id = "approval",
                                type = "control",
                                kind = LinearGraphAdapter.CONTROL_KIND_APPROVAL,
                                params = mapOf("timeout" to JsonPrimitive("PT1H"))
                            ),
                            WorkflowGraphNode("email", "action", action = "notification.email"),
                            WorkflowGraphNode("slack", "action", action = "notification.slack")
                        ),
                        edges = listOf(
                            WorkflowGraphEdge("trigger", "approval"),
                            WorkflowGraphEdge("approval", "email", branch = "approved"),
                            WorkflowGraphEdge("approval", "slack", branch = "rejected")
                        )
                    )
                )
            )
        val approvalActivity = RecordingRequestApprovalActivity(approvalId = 22)

        runWorkflow(actionActivity, persistActivity, approvalActivity).use { harness ->
            WorkflowClient.start(harness.workflow::run, input())
            harness.workflow.approve(WorkflowApprovalSignal("approval", approvalId = 21, approved = true))
            Thread.sleep(100)
            harness.workflow.approve(WorkflowApprovalSignal("approval", approvalId = 22, approved = false))
            val result = WorkflowStub.fromTyped(harness.workflow)
                .getResult(WorkflowInterpreterResult::class.java)

            assertEquals(STATUS_COMPLETE, result.status)
            assertEquals(listOf("notification.slack"), actionActivity.executedSteps)
            val approval = result.progress.first { step -> step.nodeId == "approval" }
            assertEquals(JsonPrimitive("rejected").toString(), approval.output["branch"])
        }
    }

    private fun runWorkflow(
        actionActivity: ExecuteActionActivity,
        persistActivity: PersistRunActivity,
        approvalActivity: RequestApprovalActivity = RecordingRequestApprovalActivity()
    ): TestWorkflowHarness {
        val environment = newEnvironment()
        val worker = environment.newWorker(WORKFLOW_TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(WorkflowInterpreterWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(actionActivity, persistActivity, approvalActivity)
        environment.start()
        val workflow =
            environment.workflowClient.newWorkflowStub(
                WorkflowInterpreterWorkflow::class.java,
                WorkflowOptions
                    .newBuilder()
                    .setTaskQueue(WORKFLOW_TASK_QUEUE)
                    .setWorkflowId("workflow-interpreter-test-${System.nanoTime()}")
                    .build()
            )
        return TestWorkflowHarness(environment, workflow)
    }

    private fun newEnvironment(): TestWorkflowEnvironment {
        val dataConverter =
            TemporalClientProvider(
                target = "unused:7233",
                namespace = "default",
                payloadKey = TEST_PAYLOAD_KEY
            ).dataConverter
        val options =
            TestEnvironmentOptions
                .newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions
                        .newBuilder()
                        .setDataConverter(dataConverter)
                        .build()
                ).build()
        return TestWorkflowEnvironment.newInstance(options)
    }

    private fun snapshot(
        steps: List<WorkflowStepConfig> = emptyList(),
        graph: WorkflowGraphConfig = WorkflowGraphConfig(),
        scope: Map<String, JsonElement> = emptyMap()
    ): WorkflowRunExecutionSnapshot =
        WorkflowRunExecutionSnapshot(
            runId = TEST_RUN_ID,
            organizationId = TEST_ORGANIZATION_ID,
            workflowVersionId = TEST_WORKFLOW_VERSION_ID,
            steps = steps,
            graph = graph.toRuntimeGraph(),
            triggerName = "alert.triggered",
            scope = scope.toRuntimeValues()
        )

    private fun input(): WorkflowInterpreterInput =
        WorkflowInterpreterInput(
            runId = TEST_RUN_ID,
            workflowId = TEST_WORKFLOW_ID,
            workflowVersionId = TEST_WORKFLOW_VERSION_ID,
            organizationId = TEST_ORGANIZATION_ID,
            triggerName = "alert.triggered"
        )
}

// ──── Test Harness ────

private class TestWorkflowHarness(
    private val environment: TestWorkflowEnvironment,
    val workflow: WorkflowInterpreterWorkflow
) : AutoCloseable {
    override fun close() {
        environment.close()
    }
}

// ──── Recording Fakes ────

private class RecordingExecuteActionActivity(
    private val failedSteps: Set<String> = emptySet(),
    private val throwingSteps: Set<String> = emptySet()
) : ExecuteActionActivity {
    val executedSteps: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun execute(input: ExecuteActionInput): ExecuteActionResult {
        executedSteps += input.step.name
        if (input.step.name in throwingSteps) {
            throw IllegalStateException("${input.step.name} exploded")
        }
        return if (input.step.name in failedSteps) {
            ExecuteActionResult(
                status = STATUS_FAILED,
                completedAt = TEST_COMPLETED_AT,
                errorMessage = "${input.step.name} failed"
            )
        } else {
            ExecuteActionResult(status = STATUS_COMPLETE, completedAt = TEST_COMPLETED_AT)
        }
    }
}

private class OverlapRecordingExecuteActionActivity : ExecuteActionActivity {
    private val active = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)

    override fun execute(input: ExecuteActionInput): ExecuteActionResult {
        val current = active.incrementAndGet()
        maxConcurrent.updateAndGet { previous -> maxOf(previous, current) }
        Thread.sleep(150)
        active.decrementAndGet()
        return ExecuteActionResult(status = STATUS_COMPLETE, completedAt = TEST_COMPLETED_AT)
    }
}

private class RecordingRequestApprovalActivity(
    private val approvalId: Int = 1
) : RequestApprovalActivity {
    override fun request(input: WorkflowApprovalRequestInput): WorkflowApprovalRequestResult =
        WorkflowApprovalRequestResult(
            status = STATUS_COMPLETE,
            approvalId = approvalId
        )
}

private class RecordingPersistRunActivity(
    private val snapshot: WorkflowRunExecutionSnapshot?
) : PersistRunActivity {
    val transitions = mutableListOf<String>()
    var progress = emptyList<RuntimeWorkflowRunStepProgress>()
        private set

    @Synchronized
    override fun loadRun(input: LoadRunInput): WorkflowRunExecutionSnapshot? = snapshot

    @Synchronized
    override fun markRunning(input: PersistRunProgressInput) {
        transitions += input.status
        progress = input.progress
    }

    @Synchronized
    override fun markComplete(input: PersistRunProgressInput) {
        transitions += input.status
        progress = input.progress
    }

    @Synchronized
    override fun markFailed(input: PersistRunFailureInput) {
        transitions += STATUS_FAILED
        progress = input.progress
    }

    override fun markStepRunning(input: PersistRunStepInput) = Unit

    override fun markStepComplete(input: PersistRunStepInput) = Unit

    override fun markStepFailed(input: PersistRunStepInput) = Unit
}
