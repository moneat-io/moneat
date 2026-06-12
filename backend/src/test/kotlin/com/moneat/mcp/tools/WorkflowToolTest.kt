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
import kotlin.uuid.Uuid

class WorkflowToolTest {
    // ──── Setup ────
    private val context = McpContext(
        organizationId = ORGANIZATION_ID,
        userId = USER_ID,
        tokenId = TOKEN_ID,
        scopes = setOf(McpScopes.WORKFLOW_READ, McpScopes.WORKFLOW_WRITE, McpScopes.WORKFLOW_RUN),
        sessionId = "workflow-tool-test",
    )

    // ──── Create-tool Tests ────
    @Test
    fun `create workflow accepts graph_json and once_for_template`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<CreateWorkflowRequest>()
        every { service.createWorkflow(ORGANIZATION_ID, capture(requestSlot)) } returns workflowResponse()

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
        every { service.createWorkflow(ORGANIZATION_ID, capture(requestSlot)) } returns workflowResponse()

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
        assertEquals("SEV-1", requestSlot.captured.conditions.single().value)
        assertEquals("slack", requestSlot.captured.steps.single().name)
        assertEquals("#ops", requestSlot.captured.steps.single().params["channel"])
    }

    @Test
    fun `create workflow treats explicit null optional JSON values as omitted`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<CreateWorkflowRequest>()
        every { service.createWorkflow(ORGANIZATION_ID, capture(requestSlot)) } returns workflowResponse()

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

    // ──── Run-tool Tests ────
    @Test
    fun `run workflow passes scope_json and actor user id`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<ManualWorkflowRunRequest>()
        stubWorkflowResolution(service)
        coEvery {
            service.runWorkflow(ORGANIZATION_ID, WORKFLOW_ID, capture(requestSlot), USER_ID)
        } returns workflowRunResponse()

        val result = RunWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID),
                    "scope_json" to JsonPrimitive("""{"customer_id":"acme","attempt":$SCOPE_ATTEMPT}"""),
                )
            ),
            context,
        )

        assertFalse(result.isError, result.content.firstOrNull()?.text)
        assertEquals("acme", requestSlot.captured.scope["customer_id"]?.jsonPrimitive?.content)
        assertEquals(SCOPE_ATTEMPT, requestSlot.captured.scope["attempt"]?.jsonPrimitive?.content?.toInt())
        coVerify(exactly = 1) { service.runWorkflow(ORGANIZATION_ID, WORKFLOW_ID, any(), USER_ID) }
    }

    // ──── Lifecycle Tests ────
    @Test
    fun `update workflow accepts object arguments`() = runBlocking {
        val service = mockk<WorkflowService>()
        val requestSlot = slot<UpdateWorkflowRequest>()
        stubWorkflowResolution(service)
        every { service.updateWorkflow(ORGANIZATION_ID, WORKFLOW_ID, capture(requestSlot)) } returns workflowResponse()

        val result = UpdateWorkflowTool(service).execute(
            JsonObject(
                mapOf(
                    "workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID),
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
        stubWorkflowResolution(service)
        every { service.listWorkflows(ORGANIZATION_ID) } returns listOf(workflowResponse())
        every { service.getWorkflow(ORGANIZATION_ID, WORKFLOW_ID) } returns workflowResponse()
        every { service.publishWorkflow(ORGANIZATION_ID, WORKFLOW_ID) } returns workflowResponse()
        every { service.unpublishWorkflow(ORGANIZATION_ID, WORKFLOW_ID) } returns workflowResponse()
        every { service.deleteWorkflow(ORGANIZATION_ID, WORKFLOW_ID) } returns true

        val args = JsonObject(mapOf("workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID)))

        assertFalse(ListWorkflowsTool(service).execute(JsonObject(emptyMap()), context).isError)
        assertFalse(GetWorkflowTool(service).execute(args, context).isError)
        assertFalse(PublishWorkflowTool(service).execute(args, context).isError)
        assertFalse(UnpublishWorkflowTool(service).execute(args, context).isError)
        assertFalse(DeleteWorkflowTool(service).execute(args, context).isError)

        verify(exactly = 1) { service.listWorkflows(ORGANIZATION_ID) }
        verify(exactly = 1) { service.getWorkflow(ORGANIZATION_ID, WORKFLOW_ID) }
        verify(exactly = 1) { service.publishWorkflow(ORGANIZATION_ID, WORKFLOW_ID) }
        verify(exactly = 1) { service.unpublishWorkflow(ORGANIZATION_ID, WORKFLOW_ID) }
        verify(exactly = 1) { service.deleteWorkflow(ORGANIZATION_ID, WORKFLOW_ID) }
    }

    // ──── Run-management Tests ────
    @Test
    fun `workflow run tools delegate to service and clamp limits`() = runBlocking {
        val service = mockk<WorkflowService>()
        val instanceSlot = slot<WorkflowRunInstanceRequest>()
        stubWorkflowResolution(service)
        stubRunResolution(service)
        every { service.listRuns(ORGANIZATION_ID, WORKFLOW_ID, MAX_RUN_LIMIT) } returns listOf(workflowRunResponse())
        every { service.getRun(ORGANIZATION_ID, WORKFLOW_ID, RUN_ID) } returns workflowRunResponse()
        coEvery {
            service.cancelRun(ORGANIZATION_ID, WORKFLOW_ID, RUN_ID)
        } returns WorkflowRunCancelResponse(id = RUN_RESOURCE_ID, status = "canceled")
        coEvery {
            service.createWorkflowInstance(ORGANIZATION_ID, WORKFLOW_ID, capture(instanceSlot), USER_ID)
        } returns workflowRunResponse()

        val workflowArgs = JsonObject(
            mapOf("workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID), "limit" to JsonPrimitive(OVERSIZED_RUN_LIMIT))
        )
        val runArgs = JsonObject(
            mapOf("workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID), "run_id" to JsonPrimitive(RUN_RESOURCE_ID))
        )
        val instanceArgs = JsonObject(
            mapOf(
                "workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID),
                "scope" to JsonObject(mapOf("dry_run" to JsonPrimitive(true))),
            )
        )

        assertFalse(ListWorkflowRunsTool(service).execute(workflowArgs, context).isError)
        assertFalse(GetWorkflowRunTool(service).execute(runArgs, context).isError)
        assertFalse(CancelWorkflowRunTool(service).execute(runArgs, context).isError)
        assertFalse(CreateWorkflowInstanceTool(service).execute(instanceArgs, context).isError)
        assertEquals(true, instanceSlot.captured.scope["dry_run"]?.jsonPrimitive?.boolean)
    }

    // ──── Discovery Tests ────
    @Test
    fun `catalog blueprint audit and webhook tools return data`() = runBlocking {
        val service = mockk<WorkflowService>()
        val governanceService = mockk<WorkflowGovernanceService>()
        stubWorkflowResolution(service)
        every { service.catalog() } returns WorkflowCatalog.response()
        every { service.webhookSigningInfo(ORGANIZATION_ID, WORKFLOW_ID) } returns webhookSigningResponse()
        every {
            governanceService.listAudit(ORGANIZATION_ID, WORKFLOW_ID, MAX_AUDIT_LIMIT)
        } returns listOf(workflowAuditEventResponse())

        val workflowArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID)))
        val auditArgs = JsonObject(
            mapOf("workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID), "limit" to JsonPrimitive(OVERSIZED_AUDIT_LIMIT))
        )

        assertFalse(GetWorkflowCatalogTool(service).execute(JsonObject(emptyMap()), context).isError)
        assertFalse(ListWorkflowBlueprintsTool().execute(JsonObject(emptyMap()), context).isError)
        assertFalse(
            GetWorkflowBlueprintTool().execute(JsonObject(mapOf("key" to JsonPrimitive("alert_notify_slack"))), context)
                .isError
        )
        assertFalse(ListWorkflowAuditTool(governanceService, service).execute(auditArgs, context).isError)
        assertFalse(GetWorkflowWebhookSigningTool(service).execute(workflowArgs, context).isError)
    }

    // ──── Validation Tests ────
    @Test
    fun `workflow tools return useful not found and validation errors`() = runBlocking {
        val service = mockk<WorkflowService>()
        stubWorkflowResolution(service)
        every { service.getWorkflow(ORGANIZATION_ID, WORKFLOW_ID) } returns null
        every { service.deleteWorkflow(ORGANIZATION_ID, WORKFLOW_ID) } returns false

        val workflowArgs = JsonObject(mapOf("workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID)))
        val invalidLimitArgs = JsonObject(
            mapOf("workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID), "limit" to JsonPrimitive("many"))
        )

        assertTrue(GetWorkflowTool(service).execute(workflowArgs, context).isError)
        assertTrue(DeleteWorkflowTool(service).execute(workflowArgs, context).isError)
        assertTrue(ListWorkflowRunsTool(service).execute(invalidLimitArgs, context).isError)
        assertTrue(
            GetWorkflowBlueprintTool()
                .execute(JsonObject(mapOf("key" to JsonPrimitive("missing"))), context)
                .isError
        )
        verify(exactly = 0) { service.listRuns(any<Int>(), any<Int>(), any<Int>()) }
    }

    @Test
    fun `required integer arguments distinguish missing and malformed values`() = runBlocking {
        val service = mockk<WorkflowService>()
        stubWorkflowResolution(service)

        val missingWorkflowId = GetWorkflowTool(service).execute(JsonObject(emptyMap()), context)
        val malformedWorkflowId = GetWorkflowTool(service)
            .execute(JsonObject(mapOf("workflow_id" to JsonPrimitive("bad"))), context)
        val malformedRunId = GetWorkflowRunTool(service)
            .execute(
                JsonObject(
                    mapOf(
                        "workflow_id" to JsonPrimitive(WORKFLOW_RESOURCE_ID),
                        "run_id" to JsonPrimitive("bad"),
                    )
                ),
                context,
            )

        assertTrue(missingWorkflowId.isError)
        assertTrue(missingWorkflowId.content.first().text.orEmpty().contains("workflow_id is required"))
        assertTrue(malformedWorkflowId.isError)
        assertTrue(
            malformedWorkflowId.content.first().text.orEmpty()
                .contains("workflow_id must be a valid workflow resource ID")
        )
        assertTrue(malformedRunId.isError)
        assertTrue(
            malformedRunId.content.first().text.orEmpty()
                .contains("run_id must be a valid workflow run resource ID")
        )
        verify(exactly = 0) { service.getWorkflow(any<Int>(), any<Int>()) }
        verify(exactly = 0) { service.getRun(any<Int>(), any<Int>(), any<Int>()) }
    }

    // ──── Fixtures ────
    private fun workflowResponse(): WorkflowResponse =
        WorkflowResponse(
            id = WORKFLOW_RESOURCE_ID,
            name = "Webhook enrichment",
            triggerName = "webhook",
            enabled = true,
            version = WORKFLOW_VERSION,
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
            id = RUN_RESOURCE_ID,
            workflowId = WORKFLOW_RESOURCE_ID,
            workflowVersionId = WORKFLOW_VERSION_RESOURCE_ID,
            triggerName = "manual",
            onceFor = "manual:test",
            status = "pending",
            progress = emptyList(),
            createdAt = "2026-06-01T00:00:00Z",
        )

    private fun webhookSigningResponse(): WorkflowWebhookSigningResponse =
        WorkflowWebhookSigningResponse(
            workflowId = WORKFLOW_RESOURCE_ID,
            webhookUrl = "https://api.moneat.io/v1/workflows/$WORKFLOW_RESOURCE_ID/webhook",
            signingSecret = "whsec_test",
            signatureHeader = "X-Moneat-Workflow-Signature",
            signatureFormat = "sha256=<hex>",
        )

    private fun workflowAuditEventResponse(): WorkflowAuditEventResponse =
        WorkflowAuditEventResponse(
            id = AUDIT_EVENT_RESOURCE_ID,
            workflowId = WORKFLOW_RESOURCE_ID,
            runId = RUN_RESOURCE_ID,
            action = "published",
            actorUserId = USER_RESOURCE_ID,
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
                        "value" to JsonPrimitive("SEV-1"),
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

    private fun stubWorkflowResolution(service: WorkflowService) {
        every { service.resolveWorkflowId(ORGANIZATION_ID, Uuid.parse(WORKFLOW_RESOURCE_ID)) } returns WORKFLOW_ID
    }

    private fun stubRunResolution(service: WorkflowService) {
        every {
            service.resolveRunId(ORGANIZATION_ID, WORKFLOW_ID, Uuid.parse(RUN_RESOURCE_ID))
        } returns RUN_ID
    }

    // ──── Constants ────
    private companion object {
        private const val ORGANIZATION_ID = 7
        private const val USER_ID = 42
        private const val TOKEN_ID = 99
        private const val WORKFLOW_ID = 12
        private const val WORKFLOW_RESOURCE_ID = "11111111-1111-1111-1111-111111111111"
        private const val WORKFLOW_VERSION = 1
        private const val WORKFLOW_VERSION_ID = 21
        private const val WORKFLOW_VERSION_RESOURCE_ID = "55555555-5555-5555-5555-555555555555"
        private const val RUN_ID = 44
        private const val RUN_RESOURCE_ID = "22222222-2222-2222-2222-222222222222"
        private const val AUDIT_EVENT_RESOURCE_ID = "33333333-3333-3333-3333-333333333333"
        private const val USER_RESOURCE_ID = "44444444-4444-4444-4444-444444444444"
        private const val SCOPE_ATTEMPT = 2
        private const val MAX_RUN_LIMIT = 100
        private const val OVERSIZED_RUN_LIMIT = 200
        private const val MAX_AUDIT_LIMIT = 500
        private const val OVERSIZED_AUDIT_LIMIT = 900
        private const val WEBHOOK_GRAPH_JSON =
            """{"nodes":[{"id":"trigger","type":"trigger","trigger":"webhook"}],"edges":[]}"""
        private const val CONDITIONS_JSON =
            """[{"reference":"incident.severity","operation":"equals","value":"SEV-1"}]"""
        private const val STEPS_JSON =
            """[{"name":"slack","params":{"channel":"#ops"}}]"""
    }
}
