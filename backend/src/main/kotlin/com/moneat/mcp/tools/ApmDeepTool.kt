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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val apmDeepService = DashboardService.create()

private const val DEFAULT_RELATED_LIMIT = 20
private const val MAX_RELATED_LIMIT = 100

class GetTransactionStatsTool : McpTool {
    override val name = "get_transaction_stats"
    override val description =
        "Get P50/P95/P99 latency trends for transactions"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaProjectId(),
                "period" to schemaEnum(
                    "Time period",
                    listOf("1h", "6h", "24h", "7d", "30d")
                ),
                "environment" to schemaString("Environment"),
                "operation" to schemaString("Operation type")
            )
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projectId = args.projectIdArg()
            ?: return errorResult("project_id is required")
        val period = args["period"]?.jsonPrimitive?.content
            ?: "24h"
        val environment = args["environment"]
            ?.jsonPrimitive?.content
        val operation = args["operation"]
            ?.jsonPrimitive?.content

        val stats = apmDeepService.getTransactions(
            projectId,
            period,
            environment,
            operation
        )
        return jsonResult(stats)
    }
}

class GetRelatedErrorsTool : McpTool {
    override val name = "get_related_errors"
    override val description =
        "Get errors correlated to a transaction trace"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "event_id" to schemaString("Transaction event ID"),
                "limit" to schemaNumber(
                    "Max results (default 20)"
                )
            )
        ),
        required = listOf("event_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val eventId = args["event_id"]?.jsonPrimitive?.content
            ?: return errorResult("event_id is required")
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_RELATED_LIMIT
            ).coerceIn(1, MAX_RELATED_LIMIT)

        val errors = apmDeepService
            .getRelatedErrorsForTransaction(eventId, limit)
        return jsonResult(errors)
    }
}

class GetSpanDetailsTool : McpTool {
    override val name = "get_span_details"
    override val description =
        "Get details for a specific span"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "event_id" to schemaString("Transaction event ID")
            )
        ),
        required = listOf("event_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val eventId = args["event_id"]?.jsonPrimitive?.content
            ?: return errorResult("event_id is required")
        val spans = apmDeepService.getTransactionSpans(eventId)
            ?: return errorResult("Transaction not found")
        return jsonResult(spans)
    }
}
