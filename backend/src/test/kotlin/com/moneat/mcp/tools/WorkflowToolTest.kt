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

package com.moneat.mcp.tools

import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.ManualWorkflowRunRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowAuditEventResponse
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunCancelResponse
import com.moneat.workflows.models.WorkflowRunInstanceRequest
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.models.WorkflowWebhookSigningResponse
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowToolTest {
    private val context = McpContext(
        organizationId = 7,
        userId = 42,
        tokenId = 99,
        scopes = setOf(McpScopes.WORKFLOW_READ, McpScopes.WORKFLOW_WRITE, McpScopes.WORKFLOW_RUN),
        sessionId = "workflow-tool-test",
    )

    @Test
    fun `create workflow accepts graph_json and once_for_template`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<CreateWorkflowRequest>()
        every { service.createWorkflow(7, capture(requestSlot)) } returns workflowResponse()

        val result = CreateWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Webhook enrichment"),
                    "trigger_name" to JsonPrimitive("webhook"),
                    "enabled" to JsonPrimitive(false),
                    "graph_json" to JsonPrimitive(WEBHOOK_GRAPH_JSON),
                    "once_for_template_json" to JsonPrimitive("""["webhook.event_id"]"""),
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.firstOrNull()?.text)
        assertEquals("Webhook enrichment", requestSlot.captured.name)
        assertEquals("webhook", requestSlot.captured.triggerName)
        assertFalse(requestSlot.captured.enabled)
        assertEquals(listOf("webhook.event_id"), requestSlot.captured.onceForTemplate)
        assertEquals("trigger", requestSlot.captured.graph?.nodes?.single()?.id)
    }

    @Test
    fun `create workflow rejects malformed graph_json before service call`() = runBlocking {
        val service = mockk<WorkflowService>()

        val result = CreateWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Bad graph"),
                    "trigger_name" to JsonPrimitive("webhook"),
                    "graph_json" to JsonPrimitive("""{"nodes":"""),
                )
            ),
            context,
        )

        assertTrue(result.isError)
        assertTrue(result.content.first().text.orEmpty().contains("graph must be valid JSON"))
        verify(exactly = 0) { service.createWorkflow(any(), any()) }
    }

    @Test
    fun `create workflow accepts conditions_json and steps_json`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<CreateWorkflowRequest>()
        every { service.createWorkflow(7, capture(requestSlot)) } returns workflowResponse()

        val result = CreateWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Escalate incident"),
                    "trigger_name" to JsonPrimitive("incident.created"),
                    "conditions_json" to JsonPrimitive(CONDITIONS_JSON),
                    "steps_json" to JsonPrimitive(STEPS_JSON),
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.firstOrNull()?.text)
        assertEquals("incident.severity", requestSlot.captured.conditions.single().reference)
        assertEquals("equals", requestSlot.captured.conditions.single().operation)
        assertEquals("critical", requestSlot.captured.conditions.single().value)
        assertEquals("slack", requestSlot.captured.steps.single().name)
        assertEquals("#ops", requestSlot.captured.steps.single().params["channel"])
    }

    @Test
    fun `create workflow treats explicit null optional JSON values as omitted`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<CreateWorkflowRequest>()
        every { service.createWorkflow(7, capture(requestSlot)) } returns workflowResponse()

        val result = CreateWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Null optional values"),
                    "trigger_name" to JsonPrimitive("webhook"),
                    "conditions" to JsonNull,
                    "steps" to JsonNull,
                    "graph" to JsonNull,
                    "once_for_template" to JsonNull,
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.firstOrNull()?.text)
        assertEquals(emptyList(), requestSlot.captured.conditions)
        assertEquals(emptyList(), requestSlot.captured.steps)
        assertEquals(null, requestSlot.captured.graph)
        assertEquals(emptyList(), requestSlot.captured.onceForTemplate)
    }

    @Test
    fun `run workflow passes scope_json and actor user id`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<ManualWorkflowRunRequest>()
        coEvery { service.runWorkflow(7, 12, capture(requestSlot), 42) } returns workflowRunResponse()

        val result = RunWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "workflow_id" to JsonPrimitive(12),
                    "scope_json" to JsonPrimitive("""{"customer_id":"acme","attempt":2}"""),
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.firstOrNull()?.text)
        assertEquals("acme", requestSlot.captured.scope["customer_id"]?.jsonPrimitive?.content)
        assertEquals(2, requestSlot.captured.scope["attempt"]?.jsonPrimitive?.content?.toInt())
        coVerify(exactly = 1) { service.runWorkflow(7, 12, any(), 42) }
    }

    @Test
    fun `update workflow accepts object arguments`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<UpdateWorkflowRequest>()
        every { service.updateWorkflow(7, 12, capture(requestSlot)) } returns workflowResponse()

        val result = UpdateWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "workflow_id" to JsonPrimitive(12),
                    "name" to JsonPrimitive("Updated workflow"),
                    "enabled" to JsonPrimitive(true),
                    "conditions" to workflowConditionsElement(),
                    "steps" to workflowStepsElement(),
                    "graph" to workflowGraphElement(),
                    "once_for_template" to JsonArray(listOf(JsonPrimitive("incident.id"))),
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.firstOrNull()?.text)
        assertEquals("Updated workflow", requestSlot.captured.name)
        assertEquals(true, requestSlot.captured.enabled)
        assertEquals("incident.severity", requestSlot.captured.conditions?.single()?.reference)
        assertEquals("slack", requestSlot.captured.steps?.single()?.name)
        assertEquals("trigger", requestSlot.captured.graph?.nodes?.single()?.id)
        assertEquals(listOf("incident.id"), requestSlot.captured.onceForTemplate)
    }

    @Test
    fun `workflow lifecycle tools delegate to service`() = runBlocking {
        val service = mockk<WorkflowService>()
        every { service.listWorkflows(7) } returns listOf(workflowResponse())
        every { service.getWorkflow(7, 12) } returns workflowResponse()
        every { service.publishWorkflow(7, 12) } returns workflowResponse()
        every { service.unpublishWorkflow(7, 12) } returns workflowResponse()
        every { service.deleteWorkflow(7, 12) } returns true

        val args = JsonObject(mapOf("workflow_id" to JsonPrimitive(12)))

        assertFalse(ListWorkflowsTool(service).execute(JsonObject(emptyMap()), context).isError)
        assertFalse(GetWorkflowTool(service).execute(args, context).isError)
        assertFalse(PublishWorkflowTool(service).execute(args, context).isError)
        assertFalse(UnpublishWorkflowTool(service).execute(args, context).isError)
        assertFalse(DeleteWorkflowTool(service).execute(args, context).isError)

        verify(exactly = 1) { service.listWorkflows(7) }
        verify(exactly = 1) { service.getWorkflow(7, 12) }
        verify(exactly = 1) { service.publishWorkflow(7, 12) }
        verify(exactly = 1) { service.unpublishWorkflow(7, 12) }
        verify(exactly = 1) { service.deleteWorkflow(7, 12) }
    }

    @Test
    fun `workflow run tools delegate to service and clamp limits`() = runBlocking {
        val service = mockk<WorkflowService>()
        val instanceSlot = slot<WorkflowRunInstanceRequest>()
        every { service.listRuns(7, 12, 100) } returns listOf(workflowRunResponse())
        every { service.getRun(7, 12, 44) } returns workflowRunResponse()
        coEvery { service.cancelRun(7, 12, 44) } returns WorkflowRunCancelResponse(id = 44, status = "canceled")
        coEvery { service.createWorkflowInstance(7, 12, capture(instanceSlot), 42) } returns workflowRunResponse()

        val workflowArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(12), "limit" to JsonPrimitive(200)))
        val runArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(12), "run_id" to JsonPrimitive(44)))
        val instanceArgs = JsonObject(
            mapOf(
                "workflow_id" to JsonPrimitive(12),
                "scope" to JsonObject(mapOf("dry_run" to JsonPrimitive(true))),
            )
        )

        assertFalse(ListWorkflowRunsTool(service).execute(workflowArgs, context).isError)
        assertFalse(GetWorkflowRunTool(service).execute(runArgs, context).isError)
        assertFalse(CancelWorkflowRunTool(service).execute(runArgs, context).isError)
        assertFalse(CreateWorkflowInstanceTool(service).execute(instanceArgs, context).isError)
        assertEquals(true, instanceSlot.captured.scope["dry_run"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `catalog blueprint audit and webhook tools return data`() = runBlocking {
        val service = mockk<WorkflowService>()
        val governanceService = mockk<WorkflowGovernanceService>()
        every { service.catalog() } returns WorkflowCatalog.response()
        every { service.webhookSigningInfo(7, 12) } returns webhookSigningResponse()
        every { governanceService.listAudit(7, 12, 500) } returns listOf(workflowAuditEventResponse())

        val workflowArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(12)))
        val auditArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(12), "limit" to JsonPrimitive(900)))

        assertFalse(GetWorkflowCatalogTool(service).execute(JsonObject(emptyMap()), context).isError)
        assertFalse(ListWorkflowBlueprintsTool().execute(JsonObject(emptyMap()), context).isError)
        assertFalse(
            GetWorkflowBlueprintTool().execute(JsonObject(mapOf("key" to JsonPrimitive("alert_notify_slack"))), context)
                .isError
        )
        assertFalse(ListWorkflowAuditTool(governanceService).execute(auditArgs, context).isError)
        assertFalse(GetWorkflowWebhookSigningTool(service).execute(workflowArgs, context).isError)
    }

    @Test
    fun `workflow tools return useful not found and validation errors`() = runBlocking {
        val service = mockk<WorkflowService>()
        every { service.getWorkflow(7, 12) } returns null
        every { service.deleteWorkflow(7, 12) } returns false

        val workflowArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(12)))
        val invalidLimitArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(12), "limit" to JsonPrimitive("many")))

        assertTrue(GetWorkflowTool(service).execute(workflowArgs, context).isError)
        assertTrue(DeleteWorkflowTool(service).execute(workflowArgs, context).isError)
        assertTrue(ListWorkflowRunsTool(service).execute(invalidLimitArgs, context).isError)
        assertTrue(
            GetWorkflowBlueprintTool()
                .execute(JsonObject(mapOf("key" to JsonPrimitive("missing"))), context)
                .isError
        )
        verify(exactly = 0) { service.listRuns(any(), any(), any()) }
    }

    @Test
    fun `required integer arguments distinguish missing and malformed values`() = runBlocking {
        val service = mockk<WorkflowService>()

        val missingWorkflowId = GetWorkflowTool(service).execute(JsonObject(emptyMap()), context)
        val malformedWorkflowId = GetWorkflowTool(service)
            .execute(JsonObject(mapOf("workflow_id" to JsonPrimitive("bad"))), context)
        val malformedRunId = GetWorkflowRunTool(service)
            .execute(
                JsonObject(
                    mapOf(
                        "workflow_id" to JsonPrimitive(12),
                        "run_id" to JsonPrimitive("bad"),
                    )
                ),
                context,
            )

        assertTrue(missingWorkflowId.isError)
        assertTrue(missingWorkflowId.content.first().text.orEmpty().contains("workflow_id is required"))
        assertTrue(malformedWorkflowId.isError)
        assertTrue(malformedWorkflowId.content.first().text.orEmpty().contains("workflow_id must be a valid integer"))
        assertTrue(malformedRunId.isError)
        assertTrue(malformedRunId.content.first().text.orEmpty().contains("run_id must be a valid integer"))
        verify(exactly = 0) { service.getWorkflow(any(), any()) }
        verify(exactly = 0) { service.getRun(any(), any(), any()) }
    }

    private fun workflowResponse(): WorkflowResponse =
        WorkflowResponse(
            id = 12,
            name = "Webhook enrichment",
            triggerName = "webhook",
            enabled = true,
            version = 1,
            published = false,
            conditions = emptyList(),
            steps = emptyList(),
            graph = WorkflowGraphConfig(
                nodes = listOf(WorkflowGraphNode(id = "trigger", type = "trigger", trigger = "webhook")),
                edges = emptyList(),
            ),
            onceForTemplate = listOf("webhook.event_id"),
            createdAt = "2026-06-01T00:00:00Z",
            updatedAt = "2026-06-01T00:00:00Z",
        )

    private fun workflowRunResponse(): WorkflowRunResponse =
        WorkflowRunResponse(
            id = 44,
            workflowId = 12,
            workflowVersionId = 21,
            triggerName = "manual",
            onceFor = "manual:test",
            status = "pending",
            progress = emptyList(),
            createdAt = "2026-06-01T00:00:00Z",
        )

    private fun webhookSigningResponse(): WorkflowWebhookSigningResponse =
        WorkflowWebhookSigningResponse(
            workflowId = 12,
            webhookUrl = "https://api.moneat.io/v1/workflows/12/webhook",
            signingSecret = "whsec_test",
            signatureHeader = "X-Moneat-Workflow-Signature",
            signatureFormat = "sha256=<hex>",
        )

    private fun workflowAuditEventResponse(): WorkflowAuditEventResponse =
        WorkflowAuditEventResponse(
            id = 5,
            workflowId = 12,
            runId = 44,
            action = "published",
            actorUserId = 42,
            detail = mapOf("version" to "1"),
            createdAt = "2026-06-01T00:00:00Z",
        )

    private fun workflowConditionsElement(): JsonElement =
        JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "reference" to JsonPrimitive("incident.severity"),
                        "operation" to JsonPrimitive("equals"),
                        "value" to JsonPrimitive("critical"),
                    )
                )
            )
        )

    private fun workflowStepsElement(): JsonElement =
        JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("slack"),
                        "params" to JsonObject(mapOf("channel" to JsonPrimitive("#ops"))),
                    )
                )
            )
        )

    private fun workflowGraphElement(): JsonElement =
        JsonObject(
            mapOf(
                "nodes" to JsonArray(
                    listOf(JsonObject(mapOf("id" to JsonPrimitive("trigger"), "type" to JsonPrimitive("trigger"))))
                ),
                "edges" to JsonArray(emptyList()),
            )
        )

    private companion object {
        private const val WEBHOOK_GRAPH_JSON =
            """{"nodes":[{"id":"trigger","type":"trigger","trigger":"webhook"}],"edges":[]}"""
        private const val CONDITIONS_JSON =
            """[{"reference":"incident.severity","operation":"equals","value":"critical"}]"""
        private const val STEPS_JSON =
            """[{"name":"slack","params":{"channel":"#ops"}}]"""
    }
}
