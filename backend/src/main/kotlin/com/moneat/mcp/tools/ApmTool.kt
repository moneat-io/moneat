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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private val dashboardService = DashboardService.create()

class ListTransactionsTool : McpTool {
    override val name = "list_transactions"
    override val description =
        "List transactions with performance stats for a project"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaNumber("Project ID"),
                "period" to schemaEnum(
                    "Time period",
                    listOf("1h", "6h", "24h", "7d", "30d")
                ),
                "environment" to schemaString("Environment filter"),
                "operation" to schemaString("Operation type filter")
            )
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projectId = args["project_id"]?.jsonPrimitive?.long
            ?: return errorResult("project_id is required")
        val period = args["period"]?.jsonPrimitive?.content ?: "7d"
        val env = args["environment"]?.jsonPrimitive?.content
        val op = args["operation"]?.jsonPrimitive?.content
        val txns = dashboardService.getTransactions(
            projectId,
            period,
            env,
            op
        )
        return jsonResult(txns)
    }
}

class GetTraceTool : McpTool {
    override val name = "get_trace"
    override val description =
        "Get a full transaction/trace by event ID with all spans"
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
        val txn = dashboardService.getTransaction(eventId)
            ?: return errorResult("Transaction not found: $eventId")
        return jsonResult(txn)
    }
}
