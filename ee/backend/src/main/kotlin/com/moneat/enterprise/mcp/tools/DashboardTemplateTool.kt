// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.events.repositories.ProjectRepositoryImpl
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val templateDashService = CustomDashboardService(
    DashboardFolderRepositoryImpl(),
    DashboardRepositoryImpl(),
    DashboardWidgetRepositoryImpl(),
    ProjectRepositoryImpl { col, _, _ -> col },
)
private val dashQueryEngine = DashboardQueryEngine()
private val dataDogTranslator = DataDogTranslator()
private val grafanaTranslator = GrafanaTranslator()
private val jsonParser = Json { ignoreUnknownKeys = true }
private val logger = mu.KotlinLogging.logger {}
private const val DEFAULT_RETENTION_DAYS = 90

class GetDashboardTemplatesTool : McpTool {
    override val name = "get_dashboard_templates"
    override val description =
        "List available dashboard templates"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val templates =
            templateDashService.getDefaultDashboardTemplates()
        return jsonResult(templates)
    }
}

class ImportDashboardTool : McpTool {
    override val name = "import_dashboard"
    override val description =
        "Import a dashboard from Datadog or Grafana JSON"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "format" to schemaEnum(
                    "Source format",
                    listOf("datadog", "grafana")
                ),
                "json" to schemaString(
                    "Dashboard JSON from source platform"
                )
            )
        ),
        required = listOf("format", "json")
    )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val format = args["format"]?.jsonPrimitive?.content
            ?: return errorResult("format is required")
        val jsonStr = args["json"]?.jsonPrimitive?.content
            ?: return errorResult("json is required")

        val jsonObj = try {
            jsonParser.parseToJsonElement(jsonStr) as JsonObject
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse dashboard JSON" }
            return errorResult("Invalid JSON")
        }

        val translator = when (format.lowercase()) {
            "datadog" -> dataDogTranslator
            "grafana" -> grafanaTranslator
            else -> return errorResult(
                "Unsupported format: $format"
            )
        }

        return try {
            val importResult = translator.import(jsonObj)
            val createRequest = CreateDashboardRequest(
                title = importResult.dashboard.title,
                description = importResult.dashboard.description,
                layoutType = importResult.dashboard.layoutType,
                variables = importResult.variables,
                widgets = importResult.dashboard.widgets.map { w ->
                    CreateWidgetRequest(
                        title = w.title,
                        widgetType = w.widgetType,
                        gridX = w.gridX,
                        gridY = w.gridY,
                        gridW = w.gridW,
                        gridH = w.gridH,
                        queryConfigs = w.queryConfigs,
                        displayConfig = w.displayConfig,
                        sortOrder = w.sortOrder
                    )
                }
            )
            val created = templateDashService.createDashboard(
                context.organizationId.toLong(),
                context.userId.toLong(),
                createRequest
            )
            jsonResult(created)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to import dashboard" }
            errorResult("Failed to import dashboard")
        }
    }
}

class ExecuteDashboardQueryTool : McpTool {
    override val name = "execute_dashboard_query"
    override val description =
        "Execute a query DSL against a project's data"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaNumber("Project ID"),
                "query_config" to schemaObject(
                    "Query DSL config (QueryDsl JSON)"
                ),
                "retention_days" to schemaNumber(
                    "Data retention window in days (default 90)"
                )
            )
        ),
        required = listOf("project_id", "query_config")
    )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val projectId = args["project_id"]?.jsonPrimitive
            ?.content?.toLongOrNull()
            ?: return errorResult("project_id is required")
        val queryConfigJson = args["query_config"] as? JsonObject
            ?: return errorResult("query_config must be an object")
        val retentionDays = args["retention_days"]?.jsonPrimitive
            ?.content?.toIntOrNull() ?: DEFAULT_RETENTION_DAYS

        val dsl = try {
            jsonParser.decodeFromJsonElement(
                QueryDsl.serializer(),
                queryConfigJson
            )
        } catch (e: Exception) {
            return errorResult(
                "Invalid query_config: ${e.message}"
            )
        }

        return try {
            val results = dashQueryEngine.executeQuery(
                dsl = dsl,
                projectId = projectId,
                retentionDays = retentionDays
            )
            jsonResult(results)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Dashboard query failed" }
            errorResult("Query failed: ${e.message}")
        }
    }
}
