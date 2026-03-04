// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.services.LogService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logService = LogService()

private const val DEFAULT_LOG_LIMIT = 100
private const val MAX_LOG_LIMIT = 1000
private const val DEFAULT_LOOKBACK_HOURS = 24

private val validLogLevels = setOf("trace", "debug", "info", "warn", "error", "fatal")

class QueryLogsTool : McpTool {
    override val name = "query_logs"
    override val description =
        "Search and query logs with filters. Defaults to last 24 hours if no time range given."
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "query" to schemaString("Search query string"),
                "levels" to JsonObject(
                    mapOf(
                        "type" to kotlinx.serialization.json.JsonPrimitive(
                            "array"
                        ),
                        "items" to JsonObject(
                            mapOf(
                                "type" to kotlinx.serialization.json.JsonPrimitive("string")
                            )
                        ),
                        "description" to kotlinx.serialization.json.JsonPrimitive(
                            "Log levels to filter (e.g. error, warn, info)"
                        )
                    )
                ),
                "service" to schemaString("Service name filter"),
                "environment" to schemaString("Environment filter"),
                "from" to schemaString(
                    "Start time (ISO 8601). Defaults to 24 hours ago if omitted."
                ),
                "to" to schemaString(
                    "End time (ISO 8601). Defaults to now if omitted."
                ),
                "limit" to schemaNumber("Max results (default 100)"),
                "cursor" to schemaString("Pagination cursor"),
                "system_id" to schemaString("Host/system ID filter"),
                "container_name" to schemaString("Container name filter")
            )
        )
    )

    @Suppress("CyclomaticComplexity")
    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val now = Instant.now()
        val defaultFrom = now.minus(
            DEFAULT_LOOKBACK_HOURS.toLong(), ChronoUnit.HOURS
        ).toString()
        val defaultTo = now.toString()

        val request = LogQueryRequest(
            limit = (args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_LOG_LIMIT).coerceIn(1, MAX_LOG_LIMIT),
            cursor = args["cursor"]?.jsonPrimitive?.content,
            query = args["query"]?.jsonPrimitive?.content,
            levels = args["levels"]?.jsonArray?.mapNotNull { element ->
                try {
                    element.jsonPrimitive.content.lowercase()
                        .takeIf { it in validLogLevels }
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList(),
            service = args["service"]?.jsonPrimitive?.content,
            environment = args["environment"]?.jsonPrimitive?.content,
            from = args["from"]?.jsonPrimitive?.content ?: defaultFrom,
            to = args["to"]?.jsonPrimitive?.content ?: defaultTo,
            systemId = args["system_id"]?.jsonPrimitive?.content,
            containerName = args["container_name"]?.jsonPrimitive?.content
        )

        val result = logService.queryLogs(
            context.organizationId.toLong(),
            request
        )
        return jsonResult(result)
    }
}
