// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import com.moneat.enterprise.mcp.McpToolRegistrar
import com.moneat.enterprise.mcp.models.McpContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpToolRegistryTest {

    private lateinit var registry: McpToolRegistry
    private val testContext = McpContext(
        organizationId = 1,
        userId = 1,
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
    fun `callTool dispatches to registered tool`() = runBlocking {
        val tool = object : McpTool {
            override val name = "echo"
            override val description = "Echoes input"
            override val inputSchema = InputSchema()
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

    @Test
    fun `callTool handles tool exceptions gracefully`() = runBlocking {
        val tool = object : McpTool {
            override val name = "failing"
            override val description = "Always fails"
            override val inputSchema = InputSchema()
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
            "update_dashboard",
            "delete_dashboard",
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
            "create_project",
        )

        assertEquals(84, toolsByName.size)

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
