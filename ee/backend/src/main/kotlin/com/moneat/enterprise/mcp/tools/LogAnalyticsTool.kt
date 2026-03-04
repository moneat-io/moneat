// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.logs.services.LogService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val logAnalyticsService = LogService()
private val logToolLogger = mu.KotlinLogging.logger {}

private const val DEFAULT_LOG_TOP_LIMIT = 10
private const val MAX_LOG_TOP_LIMIT = 100

private val ALLOWED_GROUP_BY_FIELDS = setOf(
    "level",
    "service_name",
    "host",
    "environment"
)
private val ALLOWED_TOP_VALUE_FIELDS = setOf(
    "message",
    "service_name",
    "level",
    "host"
)

class AggregateLogsTool : McpTool {
    override val name = "aggregate_logs"
    override val description =
        "Aggregate log volume and error rate over time"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "from" to schemaString("Start time (ISO 8601)"),
                "to" to schemaString("End time (ISO 8601)"),
                "interval" to schemaEnum(
                    "Bucket interval",
                    listOf("1m", "5m", "15m", "1h", "1d")
                ),
                "query" to schemaString("Search query"),
                "levels" to schemaString(
                    "Comma-separated log levels"
                ),
                "service" to schemaString("Service filter"),
                "group_by" to schemaString(
                    "Group by field (e.g. level, service_name)"
                )
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val groupBy = args["group_by"]?.jsonPrimitive?.content
        if (groupBy != null && groupBy !in ALLOWED_GROUP_BY_FIELDS) {
            return errorResult(
                "group_by must be one of: " +
                    ALLOWED_GROUP_BY_FIELDS.joinToString(", ")
            )
        }
        val levels = args["levels"]?.jsonPrimitive?.content
            ?.split(",")?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()
        return try {
            val result = logAnalyticsService.aggregateLogs(
                organizationId = context.organizationId.toLong(),
                from = args["from"]?.jsonPrimitive?.content,
                to = args["to"]?.jsonPrimitive?.content,
                interval = args["interval"]?.jsonPrimitive?.content,
                query = args["query"]?.jsonPrimitive?.content,
                levels = levels,
                service = args["service"]?.jsonPrimitive?.content,
                environment = null,
                tags = emptyMap(),
                excludeService = null,
                excludeEnvironment = null,
                excludeContainerName = null,
                excludeTags = emptyMap(),
                groupBy = groupBy
            )
            jsonResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logToolLogger.error(e) { "aggregateLogs failed" }
            errorResult("Failed to aggregate logs: ${e.message}")
        }
    }
}

class GetLogTopValuesTool : McpTool {
    override val name = "get_log_top_values"
    override val description =
        "Get top values for a log field (messages, services, hosts)"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "field" to schemaEnum(
                    "Field to aggregate",
                    listOf(
                        "message", "service_name",
                        "level", "host"
                    )
                ),
                "limit" to schemaNumber(
                    "Max results (default 10)"
                ),
                "from" to schemaString("Start time (ISO 8601)"),
                "to" to schemaString("End time (ISO 8601)"),
                "query" to schemaString("Search query")
            )
        ),
        required = listOf("field")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val field = args["field"]?.jsonPrimitive?.content
            ?: return errorResult("field is required")
        if (field !in ALLOWED_TOP_VALUE_FIELDS) {
            return errorResult(
                "field must be one of: " +
                    ALLOWED_TOP_VALUE_FIELDS.joinToString(", ")
            )
        }
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_LOG_TOP_LIMIT
            ).coerceIn(1, MAX_LOG_TOP_LIMIT)

        return try {
            val result = logAnalyticsService.topValues(
                organizationId = context.organizationId.toLong(),
                field = field,
                limit = limit,
                from = args["from"]?.jsonPrimitive?.content,
                to = args["to"]?.jsonPrimitive?.content,
                query = args["query"]?.jsonPrimitive?.content,
                levels = emptyList(),
                service = null,
                environment = null,
                tags = emptyMap(),
                excludeService = null,
                excludeEnvironment = null,
                excludeContainerName = null,
                excludeTags = emptyMap()
            )
            jsonResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logToolLogger.error(e) { "topValues failed" }
            errorResult("Failed to get top values: ${e.message}")
        }
    }
}

class GetLogFiltersTool : McpTool {
    override val name = "get_log_filters"
    override val description =
        "Get available log facets with counts"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "from" to schemaString("Start time (ISO 8601)"),
                "to" to schemaString("End time (ISO 8601)")
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        return try {
            val result = logAnalyticsService
                .getFilterOptionsWithCounts(
                    organizationId = context.organizationId.toLong(),
                    from = args["from"]?.jsonPrimitive?.content,
                    to = args["to"]?.jsonPrimitive?.content
                )
            jsonResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logToolLogger.error(e) { "getFilterOptionsWithCounts failed" }
            errorResult("Failed to get log filters: ${e.message}")
        }
    }
}
