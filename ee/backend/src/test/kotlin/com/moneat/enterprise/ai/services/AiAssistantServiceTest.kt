// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.enterprise.ai.llm.LlmCapability
import com.moneat.enterprise.ai.llm.LlmConfig
import com.moneat.enterprise.ai.llm.LlmCost
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
import java.util.UUID
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

        val service = AiAssistantService(registry, fakeClient, FakeAiExecutionStore())
        val writer = StringWriter()

        service.streamAssistant(
            writer = writer,
            command = assistantCommand(11, 7, "What errors happened recently?"),
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

        val service = AiAssistantService(registry, fakeClient, FakeAiExecutionStore())
        val writer = StringWriter()

        service.streamAssistant(
            writer = writer,
            command = assistantCommand(5, 99, "Create a project named edge-01"),
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
        val service = AiAssistantService(registry, fakeClient, FakeAiExecutionStore())

        service.streamAssistant(
            writer = StringWriter(),
            command = assistantCommand(1, 1, "hello"),
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

    @Test
    fun `assistant never executes a tool with malformed arguments`() = runBlocking {
        var executions = 0
        val registry = McpToolRegistry()
        registry.register(
            object : McpTool {
                override val name = "list_issues"
                override val description = "List issues"
                override val inputSchema = InputSchema()
                override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                    executions += 1
                    return ToolCallResult(content = listOf(ToolContent(text = "unexpected")))
                }
            },
        )
        val fakeClient = FakeLlmProvider(
            completions = mutableListOf(
                LlmResponse(
                    content = "",
                    toolCalls = listOf(LlmToolCall("call-invalid", "list_issues", null)),
                ),
                LlmResponse(content = "I could not run that tool."),
            ),
        )
        val writer = StringWriter()

        AiAssistantService(registry, fakeClient, FakeAiExecutionStore()).streamAssistant(
            writer = writer,
            command = assistantCommand(1, 1, "list issues"),
        )

        val events = parseEvents(writer.toString())
        assertEquals(0, executions)
        assertEquals(2, fakeClient.callCount)
        assertTrue(
            events.any { event ->
                event["type"]?.jsonPrimitive?.content == "tool_result" &&
                    event["isError"]?.jsonPrimitive?.content == "true"
            },
        )
    }

    @Test
    fun `completed run retry replays durable response without another provider call`() = runBlocking {
        val store = FakeAiExecutionStore()
        val provider = FakeLlmProvider(mutableListOf(LlmResponse(content = "Durable answer")))
        val runId = UUID.randomUUID().toString()
        val firstWriter = StringWriter()

        AiAssistantService(McpToolRegistry(), provider, store).streamAssistant(
            writer = firstWriter,
            command = assistantCommand(4, 8, "investigate", runId = runId),
        )
        val conversationId = parseEvents(firstWriter.toString())
            .first { event -> event["type"]?.jsonPrimitive?.content == "done" }["conversationId"]
            ?.jsonPrimitive
            ?.content

        val retryWriter = StringWriter()
        AiAssistantService(McpToolRegistry(), provider, store).streamAssistant(
            writer = retryWriter,
            command = assistantCommand(4, 8, "investigate", conversationId, runId),
        )

        assertEquals(1, provider.callCount)
        assertTrue(retryWriter.toString().contains("Durable answer"))
    }

    @Test
    fun `pending approval survives restart and repeated confirmation cannot repeat mutation`() = runBlocking {
        var executions = 0
        val registry = McpToolRegistry().apply {
            register(
                object : McpTool {
                    override val name = "create_project"
                    override val description = "Create project"
                    override val readOnly = false
                    override val inputSchema = InputSchema()
                    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                        executions += 1
                        return ToolCallResult(content = listOf(ToolContent(text = "created")))
                    }
                },
            )
        }
        val provider = FakeLlmProvider(
            mutableListOf(
                LlmResponse(
                    content = "",
                    toolCalls = listOf(
                        LlmToolCall("call-restart", "create_project", JsonObject(emptyMap())),
                    ),
                ),
                LlmResponse(content = "Completed after restart"),
            ),
        )
        val store = FakeAiExecutionStore()
        val writer = StringWriter()
        AiAssistantService(registry, provider, store).streamAssistant(
            writer = writer,
            command = assistantCommand(9, 12, "create it", runId = UUID.randomUUID().toString()),
        )
        val requestId = parseEvents(writer.toString())
            .first { event -> event["type"]?.jsonPrimitive?.content == "confirmation_needed" }["requestId"]
            ?.jsonPrimitive
            ?.content
        assertNotNull(requestId)

        val restartedService = AiAssistantService(registry, provider, store)
        val first = restartedService.confirmPendingAction(
            userId = 9,
            orgId = 12,
            request = AiAssistantConfirmRequest(requestId),
        )
        val replay = restartedService.confirmPendingAction(
            userId = 9,
            orgId = 12,
            request = AiAssistantConfirmRequest(requestId),
        )

        assertEquals(1, executions)
        assertEquals("Completed after restart", first.response)
        assertEquals(first, replay)
    }

    @Test
    fun `rejected mutation is durable and repeated rejection replays the response`() = runBlocking {
        var executions = 0
        val registry = McpToolRegistry().apply {
            register(
                object : McpTool {
                    override val name = "create_project"
                    override val description = "Create project"
                    override val readOnly = false
                    override val inputSchema = InputSchema()
                    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                        executions += 1
                        return ToolCallResult(content = listOf(ToolContent(text = "unexpected")))
                    }
                },
            )
        }
        val provider = FakeLlmProvider(
            mutableListOf(
                LlmResponse(
                    content = "",
                    toolCalls = listOf(
                        LlmToolCall("call-denied", "create_project", JsonObject(emptyMap())),
                    ),
                ),
                LlmResponse(content = "I left the project unchanged."),
            ),
        )
        val store = FakeAiExecutionStore()
        val writer = StringWriter()
        AiAssistantService(registry, provider, store).streamAssistant(
            writer = writer,
            command = assistantCommand(9, 12, "create it"),
        )
        val requestId = parseEvents(writer.toString())
            .first { event -> event["type"]?.jsonPrimitive?.content == "confirmation_needed" }["requestId"]
            ?.jsonPrimitive
            ?.content
        assertNotNull(requestId)

        val service = AiAssistantService(registry, provider, store)
        val first = service.confirmPendingAction(
            userId = 9,
            orgId = 12,
            request = AiAssistantConfirmRequest(requestId, approve = false),
        )
        val replay = service.confirmPendingAction(
            userId = 9,
            orgId = 12,
            request = AiAssistantConfirmRequest(requestId, approve = false),
        )

        assertEquals(0, executions)
        assertFalse(first.approved)
        assertEquals(first, replay)
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

    private fun assistantCommand(
        userId: Int,
        organizationId: Int,
        message: String,
        conversationId: String? = null,
        runId: String? = null,
    ) = AiAssistantStreamCommand(
        userId = userId,
        organizationId = organizationId,
        message = message,
        conversationId = conversationId,
        runId = runId,
        projectId = null,
        userTimezone = null,
    )
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

private class FakeAiExecutionStore : AiExecutionStore {
    private val runs = linkedMapOf<Long, MutableRun>()
    private val runIds = mutableMapOf<String, Long>()
    private val tools = mutableMapOf<Long, StoredAiToolCall>()
    private val approvals = mutableMapOf<Long, StoredAiApproval>()
    private var nextRunId = 1L
    private var nextToolId = 1L
    private var nextApprovalId = 1L

    override fun beginRun(request: StartAiRun): AiRunSession {
        val publicRunId = request.runId ?: UUID.randomUUID().toString()
        val existing = runIds[publicRunId]
        if (existing != null) return session(runs.getValue(existing), created = false)

        val internalId = nextRunId++
        val run = MutableRun(
            internalId = internalId,
            publicId = publicRunId,
            organizationId = request.organizationId,
            userId = request.userId,
            conversationId = request.conversationId ?: UUID.randomUUID().toString(),
            projectId = request.projectId,
            messages = mutableListOf(LlmMessage("user", request.message)),
        )
        runs[internalId] = run
        runIds[publicRunId] = internalId
        return session(run, created = true)
    }

    override fun resumeRun(internalRunId: Long): AiRunSession = session(runs.getValue(internalRunId), created = false)

    override fun checkpointCompletion(
        session: AiRunSession,
        round: Int,
        response: LlmResponse,
        readOnlyTools: Set<String>,
        cost: LlmCost,
    ): AiTurnCheckpoint {
        val run = runs.getValue(session.internalRunId)
        run.currentRound = round
        run.messages += LlmMessage("assistant", response.content.ifBlank { null }, toolCalls = response.toolCalls)
        if (response.toolCalls.isEmpty()) {
            run.status = AiRunStatus.COMPLETED
            run.output = response.content
        }
        val stored = response.toolCalls.map { call ->
            val id = nextToolId++
            StoredAiToolCall(
                internalId = id,
                resourceId = UUID.randomUUID().toString(),
                runId = run.internalId,
                providerCallId = call.id,
                name = call.name,
                arguments = call.arguments,
                readOnly = call.name in readOnlyTools,
                status = AiToolCallStatus.PROPOSED,
            ).also { tools[id] = it }
        }
        return AiTurnCheckpoint(response.toolCalls.isEmpty(), stored)
    }

    override fun completeRun(runId: Long, content: String) {
        val run = runs[runId] ?: return
        if (run.status in setOf(AiRunStatus.COMPLETED, AiRunStatus.FAILED, AiRunStatus.CANCELLED)) return
        run.messages += LlmMessage("assistant", content)
        run.output = content
        run.status = AiRunStatus.COMPLETED
    }

    override fun claimToolExecution(toolCall: StoredAiToolCall): Boolean {
        val current = tools[toolCall.internalId] ?: return false
        if (current.status == AiToolCallStatus.EXECUTING && current.readOnly) return true
        if (current.status != AiToolCallStatus.PROPOSED) return false
        tools[toolCall.internalId] = current.copy(status = AiToolCallStatus.EXECUTING)
        return true
    }

    override fun recordToolResult(toolCall: StoredAiToolCall, summary: String, isError: Boolean) {
        val current = tools[toolCall.internalId] ?: return
        val terminalStatus = if (current.status == AiToolCallStatus.DENIED) {
            AiToolCallStatus.DENIED
        } else if (isError) {
            AiToolCallStatus.FAILED
        } else {
            AiToolCallStatus.SUCCEEDED
        }
        tools[toolCall.internalId] = current.copy(status = terminalStatus)
        val run = runs.getValue(toolCall.runId)
        if (run.messages.none { message -> message.role == "tool" && message.toolCallId == toolCall.providerCallId }) {
            run.messages += LlmMessage("tool", summary, toolCall.providerCallId)
        }
    }

    override fun createApproval(
        session: AiRunSession,
        toolCall: StoredAiToolCall,
        requestedBy: Int,
    ): StoredAiApproval {
        approvals.values.firstOrNull { it.toolCall.internalId == toolCall.internalId }?.let { return it }
        val id = nextApprovalId++
        val approval = StoredAiApproval(
            internalId = id,
            resourceId = UUID.randomUUID().toString(),
            runId = session.internalRunId,
            runResourceId = session.runId,
            conversationResourceId = session.conversationId,
            toolCall = toolCall.copy(status = AiToolCallStatus.AWAITING_APPROVAL),
            status = AiApprovalStatus.PENDING,
            response = null,
        )
        tools[toolCall.internalId] = approval.toolCall
        approvals[id] = approval
        runs.getValue(session.internalRunId).status = AiRunStatus.WAITING_FOR_APPROVAL
        return approval
    }

    override fun claimApproval(
        resourceId: String,
        organizationId: Int,
        actorUserId: Int,
        approve: Boolean,
    ): AiApprovalClaim {
        val entry = approvals.entries.firstOrNull { it.value.resourceId == resourceId }
            ?: return AiApprovalClaim.Missing
        val approval = entry.value
        if (approval.status == AiApprovalStatus.APPROVED) {
            return approval.response
                ?.let { AiApprovalClaim.Completed(approval, it) }
                ?: AiApprovalClaim.InFlight(approval)
        }
        if (approval.status == AiApprovalStatus.DENIED) {
            return approval.response
                ?.let { AiApprovalClaim.Completed(approval, it) }
                ?: AiApprovalClaim.InFlight(approval)
        }
        val status = if (approve) AiApprovalStatus.APPROVED else AiApprovalStatus.DENIED
        val toolStatus = if (approve) AiToolCallStatus.EXECUTING else AiToolCallStatus.DENIED
        val claimed = approval.copy(
            status = status,
            toolCall = approval.toolCall.copy(status = toolStatus),
        )
        approvals[entry.key] = claimed
        tools[claimed.toolCall.internalId] = claimed.toolCall
        runs.getValue(claimed.runId).status = AiRunStatus.RUNNING
        return if (approve) AiApprovalClaim.Execute(claimed) else AiApprovalClaim.Denied(claimed)
    }

    override fun recordApprovalResponse(approvalId: Long, response: String) {
        approvals.computeIfPresent(approvalId) { _, approval -> approval.copy(response = response) }
    }

    override fun requestCancellation(runResourceId: String, organizationId: Int, actorUserId: Int): Boolean {
        val run = runIds[runResourceId]?.let(runs::get) ?: return false
        if (run.organizationId != organizationId) return false
        run.status = AiRunStatus.CANCELLED
        return true
    }

    override fun isCancellationRequested(internalRunId: Long): Boolean =
        runs[internalRunId]?.status == AiRunStatus.CANCELLED

    override fun failRun(runId: Long, code: String, message: String) {
        runs[runId]?.status = AiRunStatus.FAILED
    }

    private fun session(run: MutableRun, created: Boolean): AiRunSession {
        val pendingTools = tools.values.filter { tool ->
            tool.runId == run.internalId && tool.status in setOf(
                AiToolCallStatus.PROPOSED,
                AiToolCallStatus.AWAITING_APPROVAL,
                AiToolCallStatus.EXECUTING,
            )
        }
        val pendingApproval = approvals.values.lastOrNull { approval ->
            approval.runId == run.internalId && approval.response == null &&
                approval.status in setOf(AiApprovalStatus.PENDING, AiApprovalStatus.APPROVED)
        }
        return AiRunSession(
            internalRunId = run.internalId,
            runId = run.publicId,
            internalConversationId = run.internalId.toInt(),
            conversationId = run.conversationId,
            projectId = run.projectId,
            status = run.status,
            currentRound = run.currentRound,
            messages = run.messages.toList(),
            pendingToolCalls = pendingTools,
            pendingApproval = pendingApproval,
            outputContent = run.output,
            created = created,
        )
    }

    private data class MutableRun(
        val internalId: Long,
        val publicId: String,
        val organizationId: Int,
        val userId: Int,
        val conversationId: String,
        val projectId: Long?,
        val messages: MutableList<LlmMessage>,
        var status: AiRunStatus = AiRunStatus.RUNNING,
        var currentRound: Int = 0,
        var output: String? = null,
    )
}
