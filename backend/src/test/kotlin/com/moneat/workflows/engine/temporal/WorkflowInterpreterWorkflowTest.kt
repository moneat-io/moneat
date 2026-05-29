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

import com.moneat.workflows.models.WorkflowStepConfig
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val STATUS_COMPLETE = "complete"
private const val STATUS_FAILED = "failed"
private const val STATUS_RUNNING = "running"
private const val TEST_COMPLETED_AT = "2026-05-29T00:00:00Z"
private const val TEST_PAYLOAD_KEY = "workflow-test-payload-key-32-bytes"
private const val TEST_WORKFLOW_ID = 7
private const val TEST_WORKFLOW_VERSION_ID = 11
private const val TEST_ORGANIZATION_ID = 13

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
            assertEquals(listOf(STATUS_RUNNING, STATUS_COMPLETE), persistActivity.transitions)
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
            assertEquals(listOf(STATUS_RUNNING, STATUS_FAILED), persistActivity.transitions)
            assertEquals("notification.discord failed", result.errorMessage)
            assertEquals(STATUS_FAILED, result.progress.last().status)
            assertEquals("notification.discord failed", result.progress.last().errorMessage)
        }
    }

    private fun runWorkflow(
        actionActivity: ExecuteActionActivity,
        persistActivity: PersistRunActivity
    ): TestWorkflowHarness {
        val environment = newEnvironment()
        val worker = environment.newWorker(WORKFLOW_TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(WorkflowInterpreterWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(actionActivity, persistActivity)
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

    private fun snapshot(steps: List<WorkflowStepConfig>): WorkflowRunExecutionSnapshot =
        WorkflowRunExecutionSnapshot(
            runId = TEST_WORKFLOW_ID,
            organizationId = TEST_ORGANIZATION_ID,
            workflowVersionId = TEST_WORKFLOW_VERSION_ID,
            steps = steps,
            scope = mapOf("alert.title" to "Worker failures detected")
        )

    private fun input(): WorkflowInterpreterInput =
        WorkflowInterpreterInput(
            runId = TEST_WORKFLOW_ID,
            workflowId = TEST_WORKFLOW_ID,
            workflowVersionId = TEST_WORKFLOW_VERSION_ID,
            organizationId = TEST_ORGANIZATION_ID,
            triggerName = "alert.triggered",
            scope = mapOf("alert.title" to "Worker failures detected")
        )
}

private class TestWorkflowHarness(
    private val environment: TestWorkflowEnvironment,
    val workflow: WorkflowInterpreterWorkflow
) : AutoCloseable {
    override fun close() {
        environment.close()
    }
}

private class RecordingExecuteActionActivity(
    private val failedSteps: Set<String> = emptySet()
) : ExecuteActionActivity {
    val executedSteps: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun execute(input: ExecuteActionInput): ExecuteActionResult {
        executedSteps += input.step.name
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

private class RecordingPersistRunActivity(
    private val snapshot: WorkflowRunExecutionSnapshot?
) : PersistRunActivity {
    val transitions = mutableListOf<String>()
    var progress = emptyList<com.moneat.workflows.models.WorkflowRunStepProgress>()
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
}
