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
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.ManualWorkflowRunRequest
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRunResponse
import com.moneat.workflows.services.WorkflowService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private companion object {
        private const val WEBHOOK_GRAPH_JSON =
            """{"nodes":[{"id":"trigger","type":"trigger","trigger":"webhook"}],"edges":[]}"""
        private const val CONDITIONS_JSON =
            """[{"reference":"incident.severity","operation":"equals","value":"critical"}]"""
        private const val STEPS_JSON =
            """[{"name":"slack","params":{"channel":"#ops"}}]"""
    }
}
