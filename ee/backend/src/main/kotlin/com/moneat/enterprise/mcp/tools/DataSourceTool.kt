// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.dashboards.models.CreateCustomDataSourceRequest
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val dataSourceService = CustomDataSourceService()
private val dataSourceExecutor = CustomDataSourceExecutor()
private const val DEFAULT_QUERY_LIMIT = 100

class ListDataSourcesTool : McpTool {
    override val name = "list_datasources"
    override val description = "List custom data sources"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val sources = dataSourceService.listDataSources(
            context.organizationId.toLong()
        )
        return jsonResult(sources)
    }
}

class CreateDataSourceTool : McpTool {
    override val name = "create_datasource"
    override val description = "Create a custom data source"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "name" to schemaString("Data source name"),
                "source_type" to schemaEnum(
                    "Type",
                    listOf(
                        "postgresql", "mysql", "clickhouse",
                        "prometheus", "elasticsearch"
                    )
                ),
                "host" to schemaString("Host address"),
                "port" to schemaNumber("Port number"),
                "database_name" to schemaString("Database name"),
                "username" to schemaString("Username"),
                "password" to schemaString("Password"),
                "description" to schemaString("Description")
            )
        ),
        required = listOf("name", "source_type", "host")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val request = CreateCustomDataSourceRequest(
            name = args["name"]?.jsonPrimitive?.content
                ?: return errorResult("name is required"),
            sourceType = args["source_type"]
                ?.jsonPrimitive?.content
                ?: return errorResult("source_type is required"),
            host = args["host"]?.jsonPrimitive?.content
                ?: return errorResult("host is required"),
            port = args["port"]?.jsonPrimitive?.intOrNull,
            databaseName = args["database_name"]
                ?.jsonPrimitive?.content,
            username = args["username"]?.jsonPrimitive?.content,
            password = args["password"]?.jsonPrimitive?.content,
            description = args["description"]
                ?.jsonPrimitive?.content
        )
        val source = dataSourceService.createDataSource(
            orgId = context.organizationId.toLong(),
            userId = context.userId.toLong(),
            request = request
        )
        return jsonResult(source)
    }
}

class GetDataSourceSchemaTool : McpTool {
    override val name = "get_datasource_schema"
    override val description = "Get data source connection details"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf("datasource_id" to schemaNumber("Data source ID"))
        ),
        required = listOf("datasource_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val id = args["datasource_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("datasource_id is required")
        val source = dataSourceService.getDataSource(
            id, context.organizationId.toLong()
        ) ?: return errorResult("Data source not found")
        return jsonResult(source)
    }
}

class ExecuteDataSourceQueryTool : McpTool {
    override val name = "execute_datasource_query"
    override val description =
        "Execute a query against a custom data source"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "datasource_id" to schemaNumber("Data source ID"),
                "query" to schemaString("Query string (SQL or PromQL)"),
                "limit" to schemaNumber(
                    "Maximum rows to return (default 100)"
                )
            )
        ),
        required = listOf("datasource_id", "query")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val id = args["datasource_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("datasource_id is required")
        val query = args["query"]?.jsonPrimitive?.content
            ?: return errorResult("query is required")
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_QUERY_LIMIT
        val orgId = context.organizationId.toLong()

        val source = dataSourceService.getDataSource(id, orgId)
            ?: return errorResult("Data source not found")
        val creds = dataSourceService.getDecryptedCredentials(id, orgId)
            ?: return errorResult("Failed to decrypt credentials")
        val sourceType = CustomDataSourceType.fromString(source.sourceType)
            ?: return errorResult("Unknown source type: ${source.sourceType}")

        return try {
            val results = dataSourceExecutor.executeQuery(
                sourceId = id,
                sourceType = sourceType,
                host = source.host,
                port = source.port,
                databaseName = source.databaseName,
                credentials = creds,
                query = query,
                limit = limit
            )
            jsonResult(results)
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid query")
        }
    }
}
