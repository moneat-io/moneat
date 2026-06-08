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

import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.events.repositories.ProjectRepositoryImpl
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.services.DashboardTemplateCatalogService
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val templateDashService = CustomDashboardService(
    DashboardFolderRepositoryImpl(),
    DashboardRepositoryImpl(),
    DashboardWidgetRepositoryImpl(),
    ProjectRepositoryImpl { col, _, _ -> col },
)
private val dashboardTemplateCatalogService = DashboardTemplateCatalogService()
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
        val templates = dashboardTemplateCatalogService.listTemplates()
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
                "project_id" to schemaProjectId(),
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
    ): ToolCallResult = withRequiredProjectId(args) { projectId ->
        val queryConfigJson = args["query_config"] as? JsonObject
            ?: return@withRequiredProjectId errorResult("query_config must be an object")
        val retentionDays = args["retention_days"]?.jsonPrimitive
            ?.content?.toIntOrNull() ?: DEFAULT_RETENTION_DAYS

        val dsl = try {
            jsonParser.decodeFromJsonElement(
                QueryDsl.serializer(),
                queryConfigJson
            )
        } catch (e: Exception) {
            return@withRequiredProjectId errorResult(
                "Invalid query_config: ${e.message}"
            )
        }

        try {
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
