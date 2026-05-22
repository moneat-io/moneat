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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val DEFAULT_INFRA_LIMIT = 25
private const val MAX_INFRA_LIMIT = 500
private const val CLICKHOUSE_MAX_EXECUTION_TIME = 10

private val safePattern = Regex("^[a-zA-Z0-9_\\-.]+$")

private fun safeFilter(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    return if (safePattern.matches(value)) value else null
}

private sealed class ClickHouseResult {
    data class Success(val body: String) : ClickHouseResult()
    data class Error(val message: String) : ClickHouseResult()
}

private fun ClickHouseResult.toToolCallResult(emptyMessage: String): ToolCallResult = when (this) {
    is ClickHouseResult.Success ->
        if (body.isEmpty()) textResult(emptyMessage) else textResult(body)
    is ClickHouseResult.Error -> errorResult(message)
}

private suspend fun queryClickHouse(query: String): ClickHouseResult {
    return try {
        val response = ClickHouseClient.execute(query)
        if (response.status.value in 200..299) {
            val body = response.bodyAsText()
            if (body.isBlank()) ClickHouseResult.Success("") else ClickHouseResult.Success(body)
        } else {
            val errorBody = response.bodyAsText()
            logger.warn { "ClickHouse infra query failed (${response.status}): $errorBody" }
            ClickHouseResult.Error("Query failed: ${response.status}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.warn(e) { "ClickHouse infra query error" }
        ClickHouseResult.Error("Internal server error")
    }
}

class ListContainersTool : McpTool {
    override val name = "list_containers"
    override val description = "List containers across all hosts"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "host" to schemaString("Filter by host name"),
                "limit" to schemaNumber("Max results (default 25)")
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_INFRA_LIMIT
            ).coerceIn(1, MAX_INFRA_LIMIT)
        val host = safeFilter(args["host"]?.jsonPrimitive?.content)

        val conditions = mutableListOf(
            "organization_id = toUInt64(${context.organizationId})"
        )
        host?.let { conditions.add("host = '$it'") }

        val query = """
            SELECT host, name, image, state, cpu_percent, mem_usage, organization_id
            FROM containers
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY timestamp DESC LIMIT $limit
            SETTINGS max_execution_time=$CLICKHOUSE_MAX_EXECUTION_TIME
            FORMAT JSONEachRow
        """.trimIndent()

        val result = queryClickHouse(query)
        return result.toToolCallResult("No containers found")
    }
}

class ListProcessesTool : McpTool {
    override val name = "list_processes"
    override val description = "List processes on monitored hosts"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "host" to schemaString("Filter by host name"),
                "limit" to schemaNumber("Max results (default 25)")
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_INFRA_LIMIT
            ).coerceIn(1, MAX_INFRA_LIMIT)
        val host = safeFilter(args["host"]?.jsonPrimitive?.content)

        val conditions = mutableListOf(
            "organization_id = toUInt64(${context.organizationId})"
        )
        host?.let { conditions.add("host = '$it'") }

        val query = """
            SELECT host, pid, name, cpu_percent, mem_rss, state, organization_id
            FROM processes
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY timestamp DESC LIMIT $limit
            SETTINGS max_execution_time=$CLICKHOUSE_MAX_EXECUTION_TIME
            FORMAT JSONEachRow
        """.trimIndent()

        val result = queryClickHouse(query)
        return result.toToolCallResult("No processes found")
    }
}

class GetK8sResourcesTool : McpTool {
    override val name = "get_k8s_resources"
    override val description = "Get Kubernetes resources"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "resource_type" to schemaString(
                    "K8s resource type (e.g. pod, deployment, service)"
                ),
                "limit" to schemaNumber("Max results (default 25)")
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_INFRA_LIMIT
            ).coerceIn(1, MAX_INFRA_LIMIT)
        val resType = safeFilter(
            args["resource_type"]?.jsonPrimitive?.content
        )

        val conditions = mutableListOf(
            "organization_id = toUInt64(${context.organizationId})"
        )
        resType?.let { conditions.add("resource_type = '$it'") }

        val query = """
            SELECT resource_type, namespace, name, status, organization_id, collected_at
            FROM k8s_resources
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY collected_at DESC LIMIT $limit
            SETTINGS max_execution_time=$CLICKHOUSE_MAX_EXECUTION_TIME
            FORMAT JSONEachRow
        """.trimIndent()

        val result = queryClickHouse(query)
        return result.toToolCallResult("No K8s resources found")
    }
}

class GetNetworkConnectionsTool : McpTool {
    override val name = "get_network_connections"
    override val description = "Get network connections from hosts"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "limit" to schemaNumber("Max results (default 25)")
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_INFRA_LIMIT
            ).coerceIn(1, MAX_INFRA_LIMIT)

        val query = """
            SELECT host, local_addr, local_port, remote_addr, remote_port, protocol, direction, organization_id
            FROM network_connections
            WHERE organization_id = toUInt64(${context.organizationId})
            ORDER BY timestamp DESC LIMIT $limit
            SETTINGS max_execution_time=$CLICKHOUSE_MAX_EXECUTION_TIME
            FORMAT JSONEachRow
        """.trimIndent()

        val result = queryClickHouse(query)
        return result.toToolCallResult("No connections found")
    }
}

class GetDbmQueriesTool : McpTool {
    override val name = "get_dbm_queries"
    override val description = "Get database monitoring slow queries"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "limit" to schemaNumber("Max results (default 25)")
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val limit = (
            args["limit"]?.jsonPrimitive?.intOrNull
                ?: DEFAULT_INFRA_LIMIT
            ).coerceIn(1, MAX_INFRA_LIMIT)

        val query = """
            SELECT db_host, db_name, db_user, statement, duration_ns, organization_id, timestamp
            FROM dbm_queries
            WHERE organization_id = toUInt64(${context.organizationId})
            ORDER BY timestamp DESC LIMIT $limit
            SETTINGS max_execution_time=$CLICKHOUSE_MAX_EXECUTION_TIME
            FORMAT JSONEachRow
        """.trimIndent()

        val result = queryClickHouse(query)
        return result.toToolCallResult("No DBM queries found")
    }
}
