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

package com.moneat.mcp.protocol

import com.moneat.mcp.McpToolRegistrar
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.tools.CreateWorkflowTool
import com.moneat.mcp.tools.GetFeatureFlagAnalyticsTool
import com.moneat.mcp.tools.GetFeatureFlagTool
import com.moneat.mcp.tools.GetWorkflowTool
import com.moneat.mcp.tools.GetWorkflowWebhookSigningTool
import com.moneat.mcp.tools.RunWorkflowTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EXPECTED_CORE_MCP_TOOL_COUNT = 119

class McpToolRegistryTest {

    private lateinit var registry: McpToolRegistry
    private val fakeReadScopes = setOf(McpScopes.PROJECT_READ)
    private val testContext = McpContext(
        organizationId = 1,
        userId = 1,
        tokenId = 1,
        scopes = setOf(
            "event:read",
            "org:read",
            "project:read",
            "project:write",
            "releases:read",
            "releases:write",
            "workflow:read",
            "workflow:write",
            "workflow:run",
        ),
        sessionId = "test-session"
    )

    @BeforeEach
    fun setup() {
        registry = McpToolRegistry()
    }

    @Test
    fun `register and list tools`() {
        val tool = object : McpTool {
            override val name = "test_tool"
            override val description = "A test tool"
            override val inputSchema = InputSchema()
            override val requiredScopes = fakeReadScopes
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                return ToolCallResult(
                    content = listOf(ToolContent(text = "ok"))
                )
            }
        }

        registry.register(tool)
        val tools = registry.listTools()

        assertEquals(1, tools.size)
        assertEquals("test_tool", tools[0].name)
        assertEquals("A test tool", tools[0].description)
        assertTrue(tools[0].readOnly)
    }

    @Test
    fun `hasTool returns correct values`() {
        val tool = object : McpTool {
            override val name = "exists"
            override val description = "exists"
            override val inputSchema = InputSchema()
            override val requiredScopes = fakeReadScopes
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                return ToolCallResult(
                    content = listOf(ToolContent(text = "ok"))
                )
            }
        }

        registry.register(tool)

        assertTrue(registry.hasTool("exists"))
        assertFalse(registry.hasTool("nonexistent"))
    }

    @Test
    fun `callTool returns error for unknown tool`() = runBlocking {
        val result = registry.callTool(
            "unknown",
            JsonObject(emptyMap()),
            testContext
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("Unknown tool"))
    }

    @Test
    fun `read-only token cannot call write tool`() = runBlocking {
        val writeTool = object : McpTool {
            override val name = "create_project"
            override val description = "Creates a project"
            override val readOnly = false
            override val inputSchema = InputSchema()
            override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                return ToolCallResult(content = listOf(ToolContent(text = "created")))
            }
        }
        val readOnlyContext = testContext.copy(scopes = setOf("project:read"))

        registry.register(writeTool)
        val result = registry.callTool("create_project", JsonObject(emptyMap()), readOnlyContext)

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("project:write"))
    }

    @Test
    fun `release read scope is enforced`() = runBlocking {
        val releaseTool = object : McpTool {
            override val name = "list_releases"
            override val description = "Lists releases"
            override val inputSchema = InputSchema()
            override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                return ToolCallResult(content = listOf(ToolContent(text = "releases")))
            }
        }
        registry.register(releaseTool)

        val denied = registry.callTool(
            "list_releases",
            JsonObject(emptyMap()),
            testContext.copy(scopes = setOf("project:read"))
        )
        val allowed = registry.callTool(
            "list_releases",
            JsonObject(emptyMap()),
            testContext.copy(scopes = setOf("releases:read"))
        )

        assertTrue(denied.isError)
        assertTrue(denied.content[0].text!!.contains("releases:read"))
        assertFalse(allowed.isError)
        assertEquals("releases", allowed.content[0].text)
    }

    @Test
    fun `feature flag retrieval and analytics use event read scope`() {
        val expectedScope = setOf(McpScopes.EVENT_READ)

        assertEquals(expectedScope, McpScopes.requiredScopesFor(GetFeatureFlagTool()))
        assertEquals(expectedScope, McpScopes.requiredScopesFor(GetFeatureFlagAnalyticsTool()))
    }

    @Test
    fun `workflow tools use least privilege workflow scopes`() {
        assertEquals(setOf(McpScopes.WORKFLOW_READ), McpScopes.requiredScopesFor(GetWorkflowTool()))
        assertEquals(setOf(McpScopes.WORKFLOW_WRITE), McpScopes.requiredScopesFor(CreateWorkflowTool()))
        assertEquals(
            setOf(McpScopes.WORKFLOW_WRITE),
            McpScopes.requiredScopesFor(GetWorkflowWebhookSigningTool())
        )
        assertEquals(setOf(McpScopes.WORKFLOW_RUN), RunWorkflowTool().requiredScopes)
        assertFalse(GetWorkflowWebhookSigningTool().readOnly)
    }

    @Test
    fun `listTools filters to allowed tools`() {
        registerNamedTool("first_tool")
        registerNamedTool("second_tool")

        val tools = registry.listTools(setOf("second_tool"))

        assertEquals(1, tools.size)
        assertEquals("second_tool", tools[0].name)
    }

    @Test
    fun `callTool rejects disabled tool`() = runBlocking {
        registerNamedTool("first_tool")
        val restrictedContext = testContext.copy(allowedTools = setOf("other_tool"))

        val result = registry.callTool(
            "first_tool",
            JsonObject(emptyMap()),
            restrictedContext
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("not enabled"))
    }

    @Test
    fun `callTool dispatches to registered tool`() = runBlocking {
        val tool = object : McpTool {
            override val name = "echo"
            override val description = "Echoes input"
            override val inputSchema = InputSchema()
            override val requiredScopes = fakeReadScopes
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                val msg = args["message"]?.let {
                    (it as JsonPrimitive).content
                } ?: "no message"
                return ToolCallResult(
                    content = listOf(ToolContent(text = msg))
                )
            }
        }

        registry.register(tool)

        val result = registry.callTool(
            "echo",
            JsonObject(
                mapOf("message" to JsonPrimitive("hello"))
            ),
            testContext
        )

        assertFalse(result.isError)
        assertEquals("hello", result.content[0].text)
    }

    private fun registerNamedTool(name: String) {
        val tool = object : McpTool {
            override val name = name
            override val description = "A test tool"
            override val inputSchema = InputSchema()
            override val requiredScopes = fakeReadScopes
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                return ToolCallResult(content = listOf(ToolContent(text = "ok")))
            }
        }
        registry.register(tool)
    }

    @Test
    fun `callTool handles tool exceptions gracefully`() = runBlocking {
        val tool = object : McpTool {
            override val name = "failing"
            override val description = "Always fails"
            override val inputSchema = InputSchema()
            override val requiredScopes = fakeReadScopes
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                throw RuntimeException("Intentional failure")
            }
        }

        registry.register(tool)

        val result = registry.callTool(
            "failing",
            JsonObject(emptyMap()),
            testContext
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("Internal error"))
    }

    @Test
    fun `callTool handles IllegalArgumentException`() = runBlocking {
        val tool = object : McpTool {
            override val name = "bad_args"
            override val description = "Invalid args"
            override val inputSchema = InputSchema()
            override val requiredScopes = fakeReadScopes
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                throw IllegalArgumentException("Bad param")
            }
        }

        registry.register(tool)

        val result = registry.callTool(
            "bad_args",
            JsonObject(emptyMap()),
            testContext
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("Invalid arguments"))
    }

    @Test
    fun `multiple tools can be registered`() {
        repeat(5) { i ->
            val tool = object : McpTool {
                override val name = "tool_$i"
                override val description = "Tool $i"
                override val inputSchema = InputSchema()
                override val requiredScopes = fakeReadScopes
                override suspend fun execute(
                    args: JsonObject,
                    context: McpContext
                ): ToolCallResult {
                    return ToolCallResult(
                        content = listOf(ToolContent(text = "result_$i"))
                    )
                }
            }
            registry.register(tool)
        }

        assertEquals(5, registry.listTools().size)
    }

    @Test
    fun `enterprise tool metadata classifies write tools`() {
        McpToolRegistrar.registerAll(registry)
        val toolsByName = registry.listTools().associateBy { it.name }

        val writeTools = setOf(
            "create_agent_key",
            "delete_host",
            "create_uptime_monitor",
            "update_uptime_monitor",
            "delete_uptime_monitor",
            "pause_uptime_monitor",
            "resume_uptime_monitor",
            "create_dashboard",
            "create_dashboard_widget",
            "update_dashboard",
            "update_dashboard_widget",
            "delete_dashboard",
            "delete_dashboard_widget",
            "replace_dashboard_widgets",
            "import_dashboard",
            "execute_dashboard_query",
            "create_dashboard_alert",
            "update_dashboard_alert",
            "delete_dashboard_alert",
            "create_alert",
            "update_alert",
            "delete_alert",
            "update_alert_notification_channels",
            "create_silence_period",
            "delete_silence_period",
            "update_issue_status",
            "create_status_page",
            "update_status_page",
            "add_status_page_monitor",
            "create_status_page_incident",
            "update_status_page_incident",
            "post_incident_update",
            "update_notification_preferences",
            "create_datasource",
            "execute_datasource_query",
            "create_feature_flag_environment",
            "create_feature_flag_sdk_key",
            "create_feature_flag",
            "delete_feature_flag",
            "delete_feature_flag_segment",
            "revoke_feature_flag_sdk_key",
            "update_feature_flag",
            "update_feature_flag_config",
            "upsert_feature_flag_segment",
            "create_project",
            "cancel_workflow_run",
            "create_workflow",
            "create_workflow_instance",
            "delete_workflow",
            "get_workflow_webhook_signing",
            "publish_workflow",
            "run_workflow",
            "unpublish_workflow",
            "update_workflow",
        )

        assertEquals(EXPECTED_CORE_MCP_TOOL_COUNT, toolsByName.size)

        writeTools.forEach { name ->
            assertEquals(false, toolsByName[name]?.readOnly, "Expected $name to be classified as write")
        }

        toolsByName
            .filterKeys { name -> name !in writeTools }
            .forEach { (name, definition) ->
                assertEquals(true, definition.readOnly, "Expected $name to be classified as read")
            }
    }

    @Test
    fun `scope resolver fails closed for unmapped read tools`() {
        val unmappedReadTool = object : McpTool {
            override val name = "unmapped_read_tool"
            override val description = "No scope mapping"
            override val inputSchema = InputSchema()
            override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                return ToolCallResult(content = listOf(ToolContent(text = "unreachable")))
            }
        }

        val error = assertFailsWith<IllegalArgumentException> {
            McpScopes.requiredScopesFor(unmappedReadTool)
        }

        assertTrue(error.message!!.contains("unmapped_read_tool"))
    }

    @Test
    fun `scope resolver fails closed for unmapped write tools`() {
        val unmappedWriteTool = object : McpTool {
            override val name = "unmapped_write_tool"
            override val description = "No scope mapping"
            override val readOnly = false
            override val inputSchema = InputSchema()
            override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                return ToolCallResult(content = listOf(ToolContent(text = "unreachable")))
            }
        }

        val error = assertFailsWith<IllegalArgumentException> {
            McpScopes.requiredScopesFor(unmappedWriteTool)
        }

        assertTrue(error.message!!.contains("unmapped_write_tool"))
    }

    @Test
    fun `callTool returns timeout error for slow tool`() = runBlocking {
        // Use short timeout to avoid real wall-clock delays in tests
        val fastRegistry = McpToolRegistry(
            defaultTimeoutMs = 100,
            toolTimeouts = emptyMap()
        )
        val slowTool = object : McpTool {
            override val name = "list_containers"
            override val description = "Simulates slow infra query"
            override val inputSchema = InputSchema()
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                delay(60_000L)
                return ToolCallResult(content = listOf(ToolContent(text = "should not reach")))
            }
        }

        fastRegistry.register(slowTool)

        val result = fastRegistry.callTool(
            "list_containers",
            JsonObject(emptyMap()),
            testContext
        )

        assertTrue(result.isError)
        assertTrue(
            result.content[0].text!!.contains("timed out"),
            "Expected timeout error but got: ${result.content[0].text}"
        )
    }

    @Test
    fun `callTool propagates CancellationException without swallowing`() {
        val cancellingTool = object : McpTool {
            override val name = "cancellable"
            override val description = "Throws CancellationException"
            override val inputSchema = InputSchema()
            override val requiredScopes = fakeReadScopes
            override suspend fun execute(
                args: JsonObject,
                context: McpContext
            ): ToolCallResult {
                throw CancellationException("Test cancellation")
            }
        }

        registry.register(cancellingTool)

        var caughtCancellation: CancellationException? = null
        try {
            runBlocking {
                registry.callTool("cancellable", JsonObject(emptyMap()), testContext)
            }
        } catch (e: CancellationException) {
            caughtCancellation = e
        }
        assertTrue(
            caughtCancellation != null,
            "Expected CancellationException to propagate from callTool, not be swallowed"
        )
    }
}
