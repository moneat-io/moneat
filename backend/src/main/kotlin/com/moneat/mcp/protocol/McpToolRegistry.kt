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

import com.moneat.mcp.auth.McpAuthorization
import com.moneat.mcp.auth.McpAuthorizationException
import com.moneat.mcp.auth.McpExecutionLimiter
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
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
    val requiredScopes: Set<String> get() = McpScopes.requiredScopesFor(this)
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

    fun listTools(allowedTools: Set<String>? = null): List<ToolDefinition> {
        return tools.values
            .filter { tool -> allowedTools?.contains(tool.name) ?: true }
            .map { tool ->
                ToolDefinition(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.inputSchema,
                    readOnly = tool.readOnly,
                    requiredScopes = tool.requiredScopes,
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

        if (!context.canUseTool(name)) {
            return ToolCallResult(
                content = listOf(ToolContent(text = "Tool is not enabled for this MCP key: $name")),
                isError = true
            )
        }

        val timeoutMs = toolTimeouts[name] ?: defaultTimeoutMs
        return try {
            McpAuthorization.requireScopes(context, tool.requiredScopes)
            McpAuthorization.requireObjectAccess(args, context)
            McpExecutionLimiter.withPermit(context) {
                withTimeout(timeoutMs) { tool.execute(args, context) }
            }
        } catch (e: McpAuthorizationException) {
            logger.warn(e) { "MCP authorization failed for tool $name" }
            ToolCallResult(
                content = listOf(ToolContent(text = e.message ?: "MCP authorization failed")),
                isError = true
            )
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
