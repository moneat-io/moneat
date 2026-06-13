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

import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.events.services.DashboardService
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.logs.services.LogService
import com.moneat.logs.models.LogQueryRequest
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit

private val dashboardService = DashboardService.create()
private val logService = LogService(LogRepositoryImpl())
private val monitorService = MonitorService(HostRepositoryImpl(), HostAlertRepositoryImpl())

private const val SEARCH_LIMIT = 10
private const val SEARCH_LOOKBACK_HOURS = 24L
private val logger = mu.KotlinLogging.logger {}

class GlobalSearchTool : McpTool {
    override val name = "global_search"
    override val description =
        "Search across issues, logs, and hosts. Log search defaults to last 24 hours."
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "query" to schemaString("Search query"),
                "limit" to schemaNumber(
                    "Max results per category (default 10)"
                )
            )
        ),
        required = listOf("query")
    )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val query = args["query"]?.jsonPrimitive?.content
            ?: return errorResult("query is required")
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: SEARCH_LIMIT)
            .coerceIn(1, SEARCH_LIMIT)

        val results = StringBuilder()

        // Search logs (default to last 24 hours)
        try {
            val now = Instant.now()
            val logRequest = LogQueryRequest(
                limit = limit,
                query = query,
                from = now.minus(
                    SEARCH_LOOKBACK_HOURS,
                    ChronoUnit.HOURS
                ).toString(),
                to = now.toString()
            )
            val logResult = logService.queryLogs(
                context.organizationId.toLong(),
                logRequest
            )
            results.appendLine("=== Logs (${logResult.logs.size} found) ===")
            logResult.logs.forEach { log ->
                results.appendLine("  ${log.timestamp} [${log.level}] ${log.message}")
            }
        } catch (e: Exception) {
            logger.warn(e) { "Log search failed" }
            results.appendLine("=== Logs: search failed ===")
        }

        // Search hosts
        try {
            val hosts = monitorService.listHosts(
                context.organizationId
            )
            val matched = hosts.filter {
                (it.displayName ?: it.hostname).contains(query, ignoreCase = true)
            }.take(limit)
            results.appendLine(
                "=== Hosts (${matched.size} matched) ==="
            )
            matched.forEach { host ->
                results.appendLine("  ${host.displayName ?: host.hostname} (${host.id})")
            }
        } catch (e: Exception) {
            logger.warn(e) { "Host search failed" }
            results.appendLine("=== Hosts: search failed ===")
        }

        return textResult(results.toString())
    }
}
