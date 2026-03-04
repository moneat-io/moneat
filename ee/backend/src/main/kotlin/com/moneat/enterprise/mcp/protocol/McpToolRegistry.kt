// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import com.moneat.enterprise.mcp.models.McpContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val DEFAULT_TOOL_TIMEOUT_MS = 30_000L

private val TOOL_TIMEOUTS: Map<String, Long> = mapOf(
    "list_containers" to 10_000L,
    "list_processes" to 10_000L,
    "get_k8s_resources" to 10_000L,
    "get_network_connections" to 10_000L,
    "get_dbm_queries" to 10_000L,
    "aggregate_logs" to 15_000L,
    "get_log_top_values" to 15_000L,
    "get_log_filters" to 15_000L,
    "summarize_recent_issues" to 20_000L,
)

/**
 * Interface that all MCP tools must implement.
 */
interface McpTool {
    val name: String
    val description: String
    val inputSchema: InputSchema
    val readOnly: Boolean get() = true
    suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult
}

/**
 * Registry for MCP tools. Handles discovery and dispatch.
 * @param defaultTimeoutMs default per-tool timeout (overridable per tool via [toolTimeouts])
 * @param toolTimeouts per-tool timeout overrides keyed by tool name
 */
class McpToolRegistry(
    private val defaultTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
    private val toolTimeouts: Map<String, Long> = TOOL_TIMEOUTS
) {
    private val tools = ConcurrentHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
        logger.debug { "Registered MCP tool: ${tool.name}" }
    }

    fun listTools(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema,
                readOnly = tool.readOnly,
            )
        }
    }

    suspend fun callTool(
        name: String,
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val tool = tools[name]
            ?: return ToolCallResult(
                content = listOf(ToolContent(text = "Unknown tool: $name")),
                isError = true
            )

        val timeoutMs = toolTimeouts[name] ?: defaultTimeoutMs
        return try {
            withTimeout(timeoutMs) { tool.execute(args, context) }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "MCP tool $name timed out after ${timeoutMs}ms" }
            ToolCallResult(
                content = listOf(ToolContent(text = "Tool timed out after ${timeoutMs}ms")),
                isError = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "Invalid arguments for tool $name" }
            ToolCallResult(
                content = listOf(ToolContent(text = "Invalid arguments: ${e.message}")),
                isError = true
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Error executing MCP tool $name" }
            ToolCallResult(
                content = listOf(ToolContent(text = "Internal error: ${e.message}")),
                isError = true
            )
        }
    }

    fun hasTool(name: String): Boolean = tools.containsKey(name)
}
