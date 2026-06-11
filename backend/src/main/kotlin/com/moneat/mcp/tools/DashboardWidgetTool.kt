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

import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.dashboards.models.UpdateWidgetRequest
import com.moneat.dashboards.models.WidgetResponse
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.dashboards.repositories.WidgetData
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.handlers.withConnectionOptions
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.events.repositories.ProjectRepositoryImpl
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.RetentionPolicyService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

private val dashboardWidgetRepo = DashboardWidgetRepositoryImpl()
private val dashboardWidgetCrudService = CustomDashboardService(
    DashboardFolderRepositoryImpl(),
    DashboardRepositoryImpl(),
    dashboardWidgetRepo,
    ProjectRepositoryImpl { col, _, _ -> col },
)
private val dashboardWidgetQueryEngine = DashboardQueryEngine()
private val dashboardWidgetDataSourceService = CustomDataSourceService()
private val dashboardWidgetDataSourceExecutor = CustomDataSourceExecutor()
private val dashboardWidgetRetentionPolicyService = RetentionPolicyService()
private val dashboardWidgetProjectIdResolver = ProjectIdResolver()
private val dashboardWidgetJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
private val queryDslListSerializer = ListSerializer(QueryDsl.serializer())
private val updateWidgetListSerializer = ListSerializer(UpdateWidgetRequest.serializer())
private val displayConfigSerializer = MapSerializer(String.serializer(), String.serializer())

private const val DASHBOARD_ID_ARG = "dashboard_id"
private const val DASHBOARD_ID_LABEL = "Dashboard ID"
private const val WIDGET_ID_ARG = "widget_id"
private const val WIDGET_ID_LABEL = "Widget ID"
private const val INVALID_WIDGET_REQUEST_MESSAGE = "Invalid dashboard widget request"
private const val INVALID_QUERY_REQUEST_MESSAGE = "Invalid dashboard query request"
private const val DEFAULT_WIDGET_X = 0
private const val DEFAULT_WIDGET_WIDTH = 6
private const val DEFAULT_WIDGET_HEIGHT = 4
private const val DASHBOARD_WIDGET_DEFAULT_RETENTION_DAYS = 90

private val dashboardWidgetTypes = listOf(
    "timeseries",
    "bar",
    "donut",
    "stat",
    "gauge",
    "bargauge",
    "table",
    "toplist",
    "heatmap",
    "text",
    "section",
    "stream",
    "timeline",
    "geo_map",
    "host_map",
    "topology_map",
    "sankey",
    "treemap",
    "scatter",
    "status",
    "change",
    "custom",
    "flame_graph",
    "cost_summary",
    "iframe",
)
private val dashboardWidgetTypeSet = dashboardWidgetTypes.toSet()

private fun schemaArray(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("array"),
        "description" to JsonPrimitive(description),
        "items" to schemaObject("Array item")
    )
)

private fun JsonObject.longArg(name: String): Long? {
    val value = this[name] as? JsonPrimitive ?: return null
    return value.longOrNull ?: value.content.toLongOrNull()
}

private fun JsonObject.requiredLongArg(name: String): Long =
    longArg(name) ?: throw IllegalArgumentException("$name is required")

private fun JsonObject.requiredStringArg(name: String): String =
    optionalStringArg(name)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$name is required")

private fun JsonObject.requiredDashboardId(orgId: Long): Long {
    val resourceId = requiredStringArg(DASHBOARD_ID_ARG)
    return dashboardWidgetCrudService.resolveDashboardId(resourceId, orgId)
        ?: throw IllegalArgumentException("Dashboard not found: $resourceId")
}

private fun JsonObject.optionalIntArg(name: String): Int? {
    if (!containsKey(name)) return null
    val value = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be a valid integer")
    return value.intOrNull ?: value.content.toIntOrNull()
        ?: throw IllegalArgumentException("$name must be a valid integer")
}

private fun JsonObject.requiredIntArg(name: String): Int =
    optionalIntArg(name) ?: throw IllegalArgumentException("$name is required")

private fun JsonObject.optionalStringArg(name: String): String? {
    if (!containsKey(name)) return null
    val value = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be a string")
    require(value.isString) { "$name must be a string" }
    return value.content
}

private fun JsonObject.requiredWidgetType(): String {
    val widgetType = optionalStringArg("widget_type")
        ?: throw IllegalArgumentException("widget_type is required")
    validateWidgetType(widgetType)
    return widgetType
}

private fun validateWidgetType(widgetType: String) {
    require(widgetType in dashboardWidgetTypeSet) { "Unknown widget_type: $widgetType" }
}

private fun JsonObject.optionalQueryConfigs(): List<QueryDsl>? {
    if (!containsKey("query_configs")) return null
    val value = this["query_configs"] as? JsonArray
        ?: throw IllegalArgumentException("query_configs must be an array")
    return decodeArg("query_configs") {
        dashboardWidgetJson.decodeFromJsonElement(queryDslListSerializer, value)
    }
}

private fun JsonObject.optionalDisplayConfig(): Map<String, String>? {
    if (!containsKey("display_config")) return null
    val value = this["display_config"] as? JsonObject
        ?: throw IllegalArgumentException("display_config must be an object")
    return decodeArg("display_config") {
        dashboardWidgetJson.decodeFromJsonElement(displayConfigSerializer, value)
    }
}

private fun JsonObject.optionalVariables(): Map<String, String> {
    if (!containsKey("variables")) return emptyMap()
    val value = this["variables"] as? JsonObject
        ?: throw IllegalArgumentException("variables must be an object")
    return value.mapValues { (name, entry) ->
        val primitive = entry as? JsonPrimitive
            ?: throw IllegalArgumentException("variables.$name must be a string")
        require(primitive.isString) { "variables.$name must be a string" }
        primitive.content
    }
}

private fun JsonObject.optionalTimeRange(): TimeRangeDef? {
    if (!containsKey("time_range")) return null
    val value = this["time_range"] as? JsonObject
        ?: throw IllegalArgumentException("time_range must be an object")
    return decodeArg("time_range") {
        dashboardWidgetJson.decodeFromJsonElement(TimeRangeDef.serializer(), value)
    }
}

private fun JsonObject.requiredQueryConfig(): QueryDsl {
    val value = this["query_config"] as? JsonObject
        ?: throw IllegalArgumentException("query_config must be an object")
    return decodeArg("query_config") {
        dashboardWidgetJson.decodeFromJsonElement(QueryDsl.serializer(), value)
    }
}

private fun JsonObject.requiredReplacementWidgets(): List<UpdateWidgetRequest> {
    val value = this["widgets"] as? JsonArray
        ?: throw IllegalArgumentException("widgets must be an array")
    val widgets = decodeArg("widgets") {
        dashboardWidgetJson.decodeFromJsonElement(updateWidgetListSerializer, value)
    }
    widgets.mapNotNull { it.widgetType }.forEach(::validateWidgetType)
    return widgets
}

private fun <T> decodeArg(name: String, block: () -> T): T =
    try {
        block()
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Invalid $name: ${e.message}", e)
    }

private fun appendBottomY(widgets: List<WidgetData>): Int =
    widgets.maxOfOrNull { it.gridY + it.gridH } ?: 0

private fun nextSortOrder(widgets: List<WidgetData>): Int =
    (widgets.maxOfOrNull { it.sortOrder } ?: -1) + 1

private fun toUpdateWidgetRequest(widget: WidgetResponse): UpdateWidgetRequest =
    UpdateWidgetRequest(
        id = widget.id,
        title = widget.title,
        widgetType = widget.widgetType,
        gridX = widget.gridX,
        gridY = widget.gridY,
        gridW = widget.gridW,
        gridH = widget.gridH,
        queryConfigs = widget.queryConfigs,
        displayConfig = widget.displayConfig,
        sortOrder = widget.sortOrder
    )

private fun patchWidget(base: UpdateWidgetRequest, args: JsonObject): UpdateWidgetRequest {
    val widgetType = args.optionalStringArg("widget_type")?.also(::validateWidgetType)
    return base.copy(
        title = if (args.containsKey("title")) args.optionalStringArg("title") else base.title,
        widgetType = widgetType ?: base.widgetType,
        gridX = args.optionalIntArg("grid_x") ?: base.gridX,
        gridY = args.optionalIntArg("grid_y") ?: base.gridY,
        gridW = args.optionalIntArg("grid_w") ?: base.gridW,
        gridH = args.optionalIntArg("grid_h") ?: base.gridH,
        queryConfigs = args.optionalQueryConfigs() ?: base.queryConfigs,
        displayConfig = args.optionalDisplayConfig() ?: base.displayConfig,
        sortOrder = args.optionalIntArg("sort_order") ?: base.sortOrder
    )
}

private fun hasWidgetUpdateFields(args: JsonObject): Boolean =
    listOf(
        "title",
        "widget_type",
        "grid_x",
        "grid_y",
        "grid_w",
        "grid_h",
        "query_configs",
        "display_config",
        "sort_order",
    ).any(args::containsKey)

private fun schemaProperties(vararg pairs: Pair<String, JsonObject>): JsonObject =
    JsonObject(mapOf(*pairs))

private fun dashboardIdProperty(): Pair<String, JsonObject> =
    DASHBOARD_ID_ARG to schemaString("Dashboard resource ID")

private fun widgetIdProperty(): Pair<String, JsonObject> =
    WIDGET_ID_ARG to schemaString("Widget resource ID")

private fun widgetMutationProperties(includeWidgetId: Boolean): JsonObject {
    val properties = mutableListOf(dashboardIdProperty())
    if (includeWidgetId) {
        properties += widgetIdProperty()
    }
    properties += listOf(
        "title" to schemaString("Widget title"),
        "widget_type" to schemaEnum("Widget type", dashboardWidgetTypes),
        "grid_x" to schemaInteger("Grid x position"),
        "grid_y" to schemaInteger("Grid y position"),
        "grid_w" to schemaInteger("Grid width"),
        "grid_h" to schemaInteger("Grid height"),
        "query_configs" to schemaArray("QueryDsl array"),
        "display_config" to schemaObject("Display config"),
        "sort_order" to schemaInteger("Sort order")
    )
    return schemaProperties(*properties.toTypedArray())
}

private fun widgetTargetProperties(): JsonObject =
    schemaProperties(dashboardIdProperty(), widgetIdProperty())

private fun invalidWidgetRequestResult(e: IllegalArgumentException): ToolCallResult =
    errorResult(e.message ?: INVALID_WIDGET_REQUEST_MESSAGE)

class CreateDashboardWidgetTool : McpTool {
    override val name = "create_dashboard_widget"
    override val description = "Create a dashboard widget"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = widgetMutationProperties(includeWidgetId = false),
        required = listOf(DASHBOARD_ID_ARG, "widget_type")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult = try {
        val orgId = context.organizationId.toLong()
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
        val widgetType = args.requiredWidgetType()
        val title = args.optionalStringArg("title")
        val gridX = args.optionalIntArg("grid_x")
        val gridY = args.optionalIntArg("grid_y")
        val gridW = args.optionalIntArg("grid_w")
        val gridH = args.optionalIntArg("grid_h")
        val queryConfigs = args.optionalQueryConfigs()
        val displayConfig = args.optionalDisplayConfig()
        val dashboardId = dashboardWidgetCrudService.resolveDashboardId(dashboardResourceId, orgId)
            ?: return errorResult("Dashboard not found: $dashboardResourceId")
        dashboardWidgetCrudService.getDashboard(dashboardId, orgId, context.userId)
            ?: return errorResult("Dashboard not found: $dashboardId")

        val existingWidgets = dashboardWidgetRepo.listByDashboardId(dashboardId)
        val sortOrder = args.optionalIntArg("sort_order") ?: nextSortOrder(existingWidgets)
        val widget = CreateWidgetRequest(
            title = title,
            widgetType = widgetType,
            gridX = gridX ?: DEFAULT_WIDGET_X,
            gridY = gridY ?: appendBottomY(existingWidgets),
            gridW = gridW ?: DEFAULT_WIDGET_WIDTH,
            gridH = gridH ?: DEFAULT_WIDGET_HEIGHT,
            queryConfigs = queryConfigs ?: emptyList(),
            displayConfig = displayConfig ?: emptyMap(),
            sortOrder = sortOrder
        )
        dashboardWidgetRepo.insert(dashboardId, widget, sortOrder, Clock.System.now())
        val dashboard = dashboardWidgetCrudService.getDashboard(dashboardId, orgId, context.userId)
            ?: return errorResult("Dashboard not found: $dashboardId")
        jsonResult(dashboard)
    } catch (e: IllegalArgumentException) {
        invalidWidgetRequestResult(e)
    }
}

class UpdateDashboardWidgetTool : McpTool {
    override val name = "update_dashboard_widget"
    override val description = "Update a dashboard widget"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = widgetMutationProperties(includeWidgetId = true),
        required = listOf(DASHBOARD_ID_ARG, WIDGET_ID_ARG)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult = try {
        val orgId = context.organizationId.toLong()
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
        val widgetId = args.requiredStringArg(WIDGET_ID_ARG)
        if (!hasWidgetUpdateFields(args)) {
            return errorResult("At least one widget field must be provided to update")
        }
        val dashboardId = dashboardWidgetCrudService.resolveDashboardId(dashboardResourceId, orgId)
            ?: return errorResult("Dashboard not found: $dashboardResourceId")
        val dashboard = dashboardWidgetCrudService.getDashboard(dashboardId, orgId, context.userId)
            ?: return errorResult("Dashboard not found: $dashboardId")
        if (dashboard.widgets.none { it.id == widgetId }) {
            return errorResult("Widget not found on dashboard: $widgetId")
        }

        val widgets = dashboard.widgets.map { widget ->
            val request = toUpdateWidgetRequest(widget)
            if (widget.id == widgetId) patchWidget(request, args) else request
        }
        val updated = dashboardWidgetCrudService.updateDashboard(
            id = dashboardId,
            orgId = orgId,
            request = UpdateDashboardRequest(widgets = widgets)
        ) ?: return errorResult("Dashboard not found: $dashboardId")
        jsonResult(updated)
    } catch (e: IllegalArgumentException) {
        invalidWidgetRequestResult(e)
    }
}

class DeleteDashboardWidgetTool : McpTool {
    override val name = "delete_dashboard_widget"
    override val description = "Delete a dashboard widget"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = widgetTargetProperties(),
        required = listOf(DASHBOARD_ID_ARG, WIDGET_ID_ARG)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult = try {
        val orgId = context.organizationId.toLong()
        val dashboardResourceId = args.requiredStringArg(DASHBOARD_ID_ARG)
        val widgetId = args.requiredStringArg(WIDGET_ID_ARG)
        val dashboardId = dashboardWidgetCrudService.resolveDashboardId(dashboardResourceId, orgId)
            ?: return errorResult("Dashboard not found: $dashboardResourceId")
        val dashboard = dashboardWidgetCrudService.getDashboard(dashboardId, orgId, context.userId)
            ?: return errorResult("Dashboard not found: $dashboardId")
        if (dashboard.widgets.none { it.id == widgetId }) {
            return errorResult("Widget not found on dashboard: $widgetId")
        }
        val numericWidgetId = dashboardWidgetCrudService.resolveWidgetId(widgetId, dashboardId)
            ?: return errorResult("Widget not found on dashboard: $widgetId")
        if (!dashboardWidgetRepo.deleteById(dashboardId, numericWidgetId)) {
            return errorResult("Widget not found on dashboard: $widgetId")
        }
        val updated = dashboardWidgetCrudService.getDashboard(dashboardId, orgId, context.userId)
            ?: return errorResult("Dashboard not found: $dashboardId")
        jsonResult(updated)
    } catch (e: IllegalArgumentException) {
        invalidWidgetRequestResult(e)
    }
}

class PreviewDashboardWidgetQueryTool : McpTool {
    override val name = "preview_dashboard_widget_query"
    override val description = "Preview a dashboard widget query using dashboard resolution rules"
    override val inputSchema = InputSchema(
        properties = schemaProperties(
            dashboardIdProperty(),
            "query_config" to schemaObject("QueryDsl config"),
            "project_id" to schemaProjectId("Project resource ID"),
            "variables" to schemaObject("Variable values"),
            "time_range" to schemaObject("Time range override")
        ),
        required = listOf(DASHBOARD_ID_ARG, "query_config")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult = try {
        val orgId = context.organizationId.toLong()
        val previewArgs = args.previewQueryArgs()
        val dashboard = resolvePreviewDashboard(previewArgs.dashboardId, orgId, context.userId)
        val projectId = resolvePreviewProjectId(dashboard, previewArgs)

        val withTimeRange = previewArgs.timeRange?.let {
            previewArgs.queryConfig.copy(timeRange = it)
        } ?: previewArgs.queryConfig
        val effectiveQuery = dashboardWidgetQueryEngine.resolvePrometheusDataSource(
            dashboardWidgetQueryEngine.applyVariables(withTimeRange, previewArgs.variables),
            orgId,
            dashboardWidgetDataSourceService
        )
        val results = executePreviewQuery(effectiveQuery, orgId, projectId)
        jsonResult(results)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        errorResult(e.message ?: INVALID_QUERY_REQUEST_MESSAGE)
    }

    private data class PreviewQueryArgs(
        val dashboardId: String,
        val queryConfig: QueryDsl,
        val timeRange: TimeRangeDef?,
        val variables: Map<String, String>,
        val projectId: Long?,
        val projectResourceId: String?,
    )

    private fun JsonObject.previewQueryArgs(): PreviewQueryArgs =
        PreviewQueryArgs(
            dashboardId = requiredStringArg(DASHBOARD_ID_ARG),
            queryConfig = requiredQueryConfig(),
            timeRange = optionalTimeRange(),
            variables = optionalVariables(),
            projectId = projectIdArg("project_id"),
            projectResourceId = optionalStringArg("project_id"),
        )

    private fun resolvePreviewDashboard(
        dashboardResourceId: String,
        orgId: Long,
        userId: Int,
    ): DashboardResponse {
        val dashboardId = dashboardWidgetCrudService.resolveDashboardId(dashboardResourceId, orgId)
            ?: throw IllegalArgumentException("Dashboard not found: $dashboardResourceId")
        return dashboardWidgetCrudService.getDashboard(dashboardId, orgId, userId)
            ?: throw IllegalArgumentException("Dashboard not found: $dashboardResourceId")
    }

    private fun resolvePreviewProjectId(
        dashboard: DashboardResponse,
        args: PreviewQueryArgs,
    ): Long {
        val dashboardProjectId = dashboard.projectId
        if (dashboardProjectId != null) {
            validateRequestedProject(args.projectResourceId, dashboardProjectId)
            return dashboardWidgetProjectIdResolver.resolve(dashboardProjectId)
                ?: throw IllegalArgumentException("Dashboard project could not be resolved")
        }
        return args.projectId
            ?: throw IllegalArgumentException("project_id is required when dashboard is not scoped to a project")
    }

    private fun validateRequestedProject(
        requestedProjectId: String?,
        dashboardProjectId: String,
    ) {
        require(!(requestedProjectId != null && dashboardProjectId != requestedProjectId)) {
            "Dashboard is scoped to project $dashboardProjectId"
        }
    }

    private suspend fun executePreviewQuery(
        effectiveQuery: QueryDsl,
        orgId: Long,
        projectId: Long,
    ): List<Map<String, JsonElement>> {
        if (!dashboardWidgetQueryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
            val retentionDays = if (effectiveQuery.rawQuery == null) {
                dashboardWidgetRetentionPolicyService.getRetentionDaysForProject(projectId)
                    ?: DASHBOARD_WIDGET_DEFAULT_RETENTION_DAYS
            } else {
                DASHBOARD_WIDGET_DEFAULT_RETENTION_DAYS
            }
            return dashboardWidgetQueryEngine.executeQuery(
                dsl = effectiveQuery,
                projectId = projectId,
                retentionDays = retentionDays
            )
        }

        val sourceResourceId = dashboardWidgetQueryEngine.parseCustomDataSourceId(effectiveQuery.dataSource)
            ?: throw IllegalArgumentException("Invalid custom data source ID")
        require(dashboardWidgetDataSourceService.isValidResourceId(sourceResourceId)) {
            "Invalid custom data source ID"
        }
        val sourceId = dashboardWidgetDataSourceService.resolveDataSourceId(sourceResourceId, orgId)
            ?: throw IllegalArgumentException("Data source not found")
        val source = dashboardWidgetDataSourceService.getDataSource(sourceId, orgId)
            ?: throw IllegalArgumentException("Data source not found")
        val credentials = dashboardWidgetDataSourceService.getDecryptedCredentials(sourceId, orgId)
            ?: throw IllegalArgumentException("Failed to decrypt credentials")
        val sourceType = CustomDataSourceType.fromString(source.sourceType)
            ?: throw IllegalArgumentException("Unknown source type")
        val rawQuery = effectiveQuery.rawQuery
            ?: throw IllegalArgumentException("Custom data source queries require a rawQuery")
        return dashboardWidgetDataSourceExecutor.executeQuery(
            sourceId = sourceId,
            sourceType = sourceType,
            host = source.host,
            port = source.port,
            databaseName = source.databaseName,
            credentials = credentials.withConnectionOptions(source.extraConfig),
            query = rawQuery,
            limit = effectiveQuery.limit,
            timeRange = effectiveQuery.timeRange,
        )
    }
}

class ReplaceDashboardWidgetsTool : McpTool {
    override val name = "replace_dashboard_widgets"
    override val description = "Replace all widgets on a dashboard with an expected-count safety check"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = schemaProperties(
            dashboardIdProperty(),
            "widgets" to schemaArray("Replacement dashboard widget objects"),
            "expected_widget_count" to schemaInteger("Current widget count expected by caller")
        ),
        required = listOf(DASHBOARD_ID_ARG, "widgets", "expected_widget_count")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult = try {
        val expectedWidgetCount = args.requiredIntArg("expected_widget_count")
        val orgId = context.organizationId.toLong()
        val dashboardId = args.requiredDashboardId(orgId)
        val dashboard = dashboardWidgetCrudService.getDashboard(dashboardId, orgId, context.userId)
            ?: return errorResult("Dashboard not found: $dashboardId")
        val currentCount = dashboard.widgets.size
        if (currentCount != expectedWidgetCount) {
            return errorResult(
                "Dashboard has $currentCount widgets but expected_widget_count is $expectedWidgetCount. " +
                    "Read the dashboard first to get current state."
            )
        }
        val replacementWidgets = args.requiredReplacementWidgets()
        val updated = dashboardWidgetCrudService.updateDashboard(
            id = dashboardId,
            orgId = orgId,
            request = UpdateDashboardRequest(widgets = replacementWidgets)
        ) ?: return errorResult("Dashboard not found: $dashboardId")
        jsonResult(updated)
    } catch (e: IllegalArgumentException) {
        invalidWidgetRequestResult(e)
    }
}
