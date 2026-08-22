// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.enterprise.ai.llm.LlmCapability
import com.moneat.enterprise.ai.llm.LlmConfig
import com.moneat.enterprise.ai.llm.LlmMessage
import com.moneat.enterprise.ai.llm.LlmProvider
import com.moneat.enterprise.ai.llm.LlmResponse
import com.moneat.enterprise.ai.llm.LlmTool
import com.moneat.enterprise.ai.llm.LlmToolCall
import com.moneat.enterprise.ai.models.AiAssistantConfirmRequest
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.protocol.ToolContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiAssistantServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `stream executes read-only tool and emits response`() = runBlocking {
        var observedScopes = emptySet<String>()
        val registry = McpToolRegistry()
        registry.register(
            object : McpTool {
                override val name = "list_issues"
                override val description = "List issues"
                override val inputSchema = InputSchema()
                override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                    observedScopes = context.scopes
                    return ToolCallResult(content = listOf(ToolContent(text = "Found 2 issues")))
                }
            },
        )

        val fakeClient = FakeLlmProvider(
            completions = mutableListOf(
                LlmResponse(
                    content = "",
                    toolCalls = listOf(
                        LlmToolCall(
                            id = "call-1",
                            name = "list_issues",
                            arguments = JsonObject(mapOf("limit" to JsonPrimitive(5))),
                        ),
                    ),
                ),
                LlmResponse(content = "There are 2 active issues."),
            ),
        )

        val service = AiAssistantService(registry, fakeClient)
        val writer = StringWriter()

        service.streamAssistant(
            writer = writer,
            userId = 11,
            orgId = 7,
            message = "What errors happened recently?",
            conversationId = null,
        )

        val events = parseEvents(writer.toString())
        assertTrue(events.any { it["type"]?.jsonPrimitive?.content == "tool_invoking" })
        assertTrue(events.any { it["type"]?.jsonPrimitive?.content == "tool_result" })
        assertTrue(events.any { it["type"]?.jsonPrimitive?.content == "response" })
        assertTrue(events.any { it["type"]?.jsonPrimitive?.content == "done" })
        assertEquals(setOf(McpScopes.EVENT_READ), observedScopes)
        assertEquals(2, fakeClient.callCount)
    }

    @Test
    fun `write tool requires confirmation before execution`() = runBlocking {
        var writeExecutions = 0
        var observedScopes = emptySet<String>()
        val registry = McpToolRegistry()
        registry.register(
            object : McpTool {
                override val name = "create_project"
                override val description = "Create project"
                override val readOnly = false
                override val inputSchema = InputSchema()
                override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                    observedScopes = context.scopes
                    writeExecutions += 1
                    return ToolCallResult(content = listOf(ToolContent(text = "Project created")))
                }
            },
        )

        val fakeClient = FakeLlmProvider(
            completions = mutableListOf(
                LlmResponse(
                    content = "I can create that host.",
                    toolCalls = listOf(
                        LlmToolCall(
                            id = "call-2",
                            name = "create_project",
                            arguments = JsonObject(mapOf("name" to JsonPrimitive("edge-01"))),
                        ),
                    ),
                ),
                LlmResponse(content = "Done. Project edge-01 has been created."),
            ),
        )

        val service = AiAssistantService(registry, fakeClient)
        val writer = StringWriter()

        service.streamAssistant(
            writer = writer,
            userId = 5,
            orgId = 99,
            message = "Create a project named edge-01",
            conversationId = null,
        )

        val events = parseEvents(writer.toString())
        val confirmationEvent = events.firstOrNull {
            it["type"]?.jsonPrimitive?.content == "confirmation_needed"
        }

        assertNotNull(confirmationEvent)
        assertFalse(events.any { it["type"]?.jsonPrimitive?.content == "response" })
        assertEquals(0, writeExecutions)

        val requestId = confirmationEvent["requestId"]?.jsonPrimitive?.content
        assertNotNull(requestId)

        val confirmResponse = service.confirmPendingAction(
            userId = 5,
            orgId = 99,
            request = AiAssistantConfirmRequest(requestId = requestId, approve = true),
        )

        assertEquals(1, writeExecutions)
        assertEquals(setOf(McpScopes.PROJECT_WRITE), observedScopes)
        assertTrue(confirmResponse.approved)
        assertEquals("create_project", confirmResponse.tool)
        assertTrue(confirmResponse.response.contains("Project edge-01"))
    }

    @Test
    fun `assistant normalizes array schema without items for function calling`() = runBlocking {
        val registry = McpToolRegistry()
        registry.register(
            object : McpTool {
                override val name = "query_logs"
                override val description = "Query logs"
                override val inputSchema = InputSchema(
                    properties = JsonObject(
                        mapOf(
                            "levels" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("array"),
                                    "description" to JsonPrimitive("Log levels"),
                                ),
                            ),
                        ),
                    ),
                )

                override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                    return ToolCallResult(content = listOf(ToolContent(text = "ok")))
                }
            },
        )

        val fakeClient = FakeLlmProvider(
            completions = mutableListOf(
                LlmResponse(content = "No tool call required."),
            ),
        )
        val service = AiAssistantService(registry, fakeClient)

        service.streamAssistant(
            writer = StringWriter(),
            userId = 1,
            orgId = 1,
            message = "hello",
            conversationId = null,
        )

        val queryLogsFunction = fakeClient
            .capturedTools
            .firstOrNull { function -> function.name == "query_logs" }
        assertNotNull(queryLogsFunction)

        val levelsSchema = queryLogsFunction
            .parameters["properties"]
            ?.jsonObject
            ?.get("levels")
            ?.jsonObject
        assertNotNull(levelsSchema)
        assertEquals("array", levelsSchema["type"]?.jsonPrimitive?.content)
        assertEquals("string", levelsSchema["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    private fun parseEvents(raw: String): List<JsonObject> {
        val events = mutableListOf<JsonObject>()
        raw.lineSequence()
            .filter { line -> line.startsWith("data: ") }
            .map { line -> line.removePrefix("data: ") }
            .forEach { payload ->
                try {
                    events += json.parseToJsonElement(payload).jsonObject
                } catch (_: Exception) {
                    // Ignore malformed SSE entries in test parsing.
                }
            }
        return events
    }
}

private class FakeLlmProvider(
    private val completions: MutableList<LlmResponse>,
) : LlmProvider {
    var callCount: Int = 0
        private set
    var capturedTools: List<LlmTool> = emptyList()
        private set

    override suspend fun chatCompletion(
        messages: List<LlmMessage>,
        config: LlmConfig,
        tools: List<LlmTool>,
    ): LlmResponse {
        callCount += 1
        capturedTools = tools
        if (completions.isEmpty()) {
            return LlmResponse(content = "No more completions configured.")
        }
        return completions.removeAt(0)
    }

    override fun provider(): String = "fake"

    override fun model(): String = "fake-model"

    override fun isEnabled(): Boolean = true

    override fun capabilities(): Set<LlmCapability> = LlmCapability.entries.toSet()
}
