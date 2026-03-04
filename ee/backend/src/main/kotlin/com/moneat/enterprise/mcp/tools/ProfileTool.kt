// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.config.ClickHouseClient
import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val DEFAULT_PROFILE_LIMIT = 50
private const val MAX_PROFILE_LIMIT = 500

class ListProfilesTool : McpTool {
    override val name = "list_profiles"
    override val description = "List profiling data with filters"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "service" to schemaString("Service name filter"),
                "environment" to schemaString("Environment filter"),
                "limit" to schemaNumber("Max results (default 50)")
            )
        )
    )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_PROFILE_LIMIT)
            .coerceIn(1, MAX_PROFILE_LIMIT)
        val service = args["service"]?.jsonPrimitive?.content
        val env = args["environment"]?.jsonPrimitive?.content

        val validPattern = Regex("^[a-zA-Z0-9_\\-.]+$")
        if (service != null && !service.matches(validPattern)) {
            return errorResult("Invalid service value: $service")
        }
        if (env != null && !env.matches(validPattern)) {
            return errorResult("Invalid environment value: $env")
        }

        val conditions = mutableListOf(
            "organization_id = toUInt64(${context.organizationId})"
        )
        service?.let {
            conditions.add("service = '$it'")
        }
        env?.let {
            conditions.add("environment = '$it'")
        }

        val query = """
            SELECT * FROM profiles
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY timestamp DESC LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            if (response.status.value in 200..299) {
                textResult(response.bodyAsText())
            } else {
                textResult("No profiles found")
            }
        } catch (e: Exception) {
            logger.warn { "Profile query error: ${e.message}" }
            textResult("No profiles found")
        }
    }
}
