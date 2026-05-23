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

import com.moneat.config.ClickHouseClient
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
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
