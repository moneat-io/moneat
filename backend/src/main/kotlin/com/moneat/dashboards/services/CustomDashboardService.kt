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

package com.moneat.dashboards.services

import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.CreateFolderRequest
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.FolderResponse
import com.moneat.dashboards.models.GroupByDef
import com.moneat.dashboards.models.GroupByType
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.OrderByDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.SearchProjectResponse
import com.moneat.dashboards.models.SearchResponse
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.dashboards.models.UpdateFolderRequest
import com.moneat.dashboards.models.WidgetResponse
import com.moneat.dashboards.repositories.DashboardFolderRepository
import com.moneat.dashboards.repositories.DashboardRepository
import com.moneat.dashboards.repositories.DashboardWidgetRepository
import com.moneat.dashboards.repositories.DashboardWithFavoriteFlag
import com.moneat.events.repositories.ProjectRepository
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class CustomDashboardService(
    private val folderRepository: DashboardFolderRepository,
    private val dashboardRepository: DashboardRepository,
    private val dashboardWidgetRepository: DashboardWidgetRepository,
    private val projectRepository: ProjectRepository,
) {

    private fun parseVariables(variablesJson: String): List<DashboardVariable> {
        return try {
            json.decodeFromString<List<DashboardVariable>>(variablesJson)
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    private fun mapToResponse(d: DashboardWithFavoriteFlag, widgets: List<WidgetResponse>): DashboardResponse =
        DashboardResponse(
            id = d.id,
            orgId = d.orgId,
            projectId = d.projectId,
            folderId = d.folderId,
            title = d.title,
            description = d.description,
            layoutType = d.layoutType,
            isDefault = d.isDefault,
            isFavorited = d.isFavorited,
            variables = parseVariables(d.variables),
            createdBy = d.createdBy,
            createdAt = d.createdAt,
            updatedAt = d.updatedAt,
            widgets = widgets
        )

    private fun loadWidgets(dashboardId: Long): List<WidgetResponse> =
        dashboardWidgetRepository.listByDashboardId(dashboardId).map { wd ->
            val queryConfigs: List<QueryDsl> = try {
                json.decodeFromString(wd.queryConfigs)
            } catch (_: SerializationException) {
                try {
                    listOf(json.decodeFromString<QueryDsl>(wd.queryConfig))
                } catch (_: SerializationException) { emptyList() }
            }
            WidgetResponse(
                id = wd.id,
                dashboardId = wd.dashboardId,
                title = wd.title,
                widgetType = wd.widgetType,
                gridX = wd.gridX,
                gridY = wd.gridY,
                gridW = wd.gridW,
                gridH = wd.gridH,
                queryConfigs = queryConfigs,
                displayConfig = try {
                    json.decodeFromString(wd.displayConfig)
                } catch (_: SerializationException) {
                    emptyMap()
                },
                sortOrder = wd.sortOrder
            )
        }

    fun listDashboards(orgId: Long, projectId: Long? = null, userId: Int? = null): List<DashboardResponse> =
        dashboardRepository.list(orgId, projectId, userId).map { d ->
            mapToResponse(d, loadWidgets(d.id))
        }

    fun getDashboard(id: Long, orgId: Long, userId: Int? = null): DashboardResponse? {
        val d = dashboardRepository.getById(id, orgId, userId) ?: return null
        return mapToResponse(d, loadWidgets(id))
    }

    fun createDashboard(orgId: Long, userId: Long, request: CreateDashboardRequest): DashboardResponse {
        val data = dashboardRepository.create(orgId, userId, request)
        val now = Clock.System.now()
        val widgets = request.widgets.mapIndexed { index, widget ->
            val sortOrder = widget.sortOrder.takeIf { it > 0 } ?: index
            val widgetId = dashboardWidgetRepository.insert(data.id, widget, sortOrder, now)
            WidgetResponse(
                id = widgetId,
                dashboardId = data.id,
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
        }
        return DashboardResponse(
            id = data.id,
            orgId = data.orgId,
            projectId = data.projectId,
            title = data.title,
            description = data.description,
            layoutType = data.layoutType,
            isDefault = data.isDefault,
            variables = parseVariables(data.variables),
            createdBy = data.createdBy,
            createdAt = data.createdAt,
            updatedAt = data.updatedAt,
            widgets = widgets
        )
    }

    fun updateDashboard(id: Long, orgId: Long, request: UpdateDashboardRequest): DashboardResponse? {
        val updated = dashboardRepository.update(id, orgId, request)
        if (!updated) return null
        if (request.widgets != null) {
            val now = Clock.System.now()
            val keptIds = dashboardWidgetRepository.bulkUpsert(id, request.widgets, now)
            dashboardWidgetRepository.deleteNotIn(id, keptIds)
        }
        return getDashboard(id, orgId, null)
    }

    fun moveDashboardToFolder(id: Long, orgId: Long, folderId: Long?): Boolean =
        dashboardRepository.moveToFolder(id, orgId, folderId)

    fun deleteDashboard(id: Long, orgId: Long): Boolean =
        dashboardRepository.delete(id, orgId)

    fun listFolders(orgId: Long): List<FolderResponse> =
        folderRepository.listByOrgId(orgId).map { row ->
            FolderResponse(
                id = row.id,
                orgId = row.orgId,
                name = row.name,
                color = row.color,
                sortOrder = row.sortOrder,
                createdAt = row.createdAt.toString(),
                updatedAt = row.updatedAt.toString()
            )
        }

    fun createFolder(orgId: Long, request: CreateFolderRequest): FolderResponse {
        val id = folderRepository.create(orgId, request.name, request.color, request.sortOrder)
        return FolderResponse(
            id = id,
            orgId = orgId,
            name = request.name,
            color = request.color,
            sortOrder = request.sortOrder,
            createdAt = Clock.System.now().toString(),
            updatedAt = Clock.System.now().toString()
        )
    }

    fun updateFolder(id: Long, orgId: Long, request: UpdateFolderRequest): FolderResponse? {
        val row = folderRepository.getByIdAndOrgId(id, orgId) ?: return null
        folderRepository.update(id, orgId, request.name, request.color, request.sortOrder)
        return FolderResponse(
            id = id,
            orgId = row.orgId,
            name = request.name ?: row.name,
            color = request.color ?: row.color,
            sortOrder = request.sortOrder ?: row.sortOrder,
            createdAt = row.createdAt.toString(),
            updatedAt = Clock.System.now().toString()
        )
    }

    fun deleteFolder(id: Long, orgId: Long): Boolean =
        folderRepository.delete(id, orgId) > 0

    fun toggleFavorite(userId: Int, dashboardId: Long, orgId: Long): Boolean =
        dashboardRepository.toggleFavorite(userId, dashboardId, orgId)

    fun search(orgId: Long, userId: Int?, query: String): SearchResponse {
        if (query.isBlank()) return SearchResponse()
        val pattern = "%${query.trim().lowercase()}%"
        val dashboards = dashboardRepository.search(orgId, userId, pattern).map { d ->
            mapToResponse(d, loadWidgets(d.id))
        }
        val projects = projectRepository.searchProjectsByName(orgId.toInt(), pattern, limit = 10).map { row ->
            SearchProjectResponse(id = row.projectId, resourceId = row.resourceId, name = row.name)
        }
        return SearchResponse(dashboards = dashboards, projects = projects)
    }

    fun getDefaultDashboardTemplates(): List<CreateDashboardRequest> = listOf(
        createErrorOverviewTemplate(),
        createPerformanceTemplate(),
        createLogAnalysisTemplate(),
        createSystemHealthTemplate()
    )

    private fun createErrorOverviewTemplate() = CreateDashboardRequest(
        title = "Error Overview",
        description = "Overview of errors across all services",
        widgets = listOf(
            CreateWidgetRequest(
                title = "Error Count Over Time",
                widgetType = "timeseries",
                gridX = 0,
                gridY = 0,
                gridW = 8,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "events",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "error_count")),
                        groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto")),
                        filters = listOf(FilterDef("level", FilterOp.EQ, "error"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Total Errors (24h)",
                widgetType = "stat",
                gridX = 8,
                gridY = 0,
                gridW = 4,
                gridH = 2,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "events",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total_errors")),
                        filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                        timeRange = TimeRangeDef("now-24h", "now")
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Unique Users Affected",
                widgetType = "stat",
                gridX = 8,
                gridY = 2,
                gridW = 4,
                gridH = 2,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "events",
                        metrics = listOf(MetricDef(AggFunction.UNIQ, "user_id", "affected_users")),
                        filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                        timeRange = TimeRangeDef("now-24h", "now")
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Errors by Environment",
                widgetType = "donut",
                gridX = 0,
                gridY = 4,
                gridW = 6,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "events",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                        groupBy = listOf(GroupByDef("environment", GroupByType.FIELD)),
                        filters = listOf(FilterDef("level", FilterOp.EQ, "error"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Top Error Messages",
                widgetType = "toplist",
                gridX = 6,
                gridY = 4,
                gridW = 6,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "events",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                        groupBy = listOf(GroupByDef("transaction_name", GroupByType.FIELD)),
                        filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                        orderBy = OrderByDef("count", "desc"),
                        limit = 10
                    )
                ),
            )
        )
    )

    private fun createPerformanceTemplate() = CreateDashboardRequest(
        title = "Performance Overview",
        description = "Application performance metrics and trends",
        widgets = listOf(
            CreateWidgetRequest(
                title = "P95 Response Time",
                widgetType = "timeseries",
                gridX = 0,
                gridY = 0,
                gridW = 8,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "spans",
                        metrics = listOf(MetricDef(AggFunction.P95, "duration_ms", "p95_duration")),
                        groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Avg Response Time",
                widgetType = "stat",
                gridX = 8,
                gridY = 0,
                gridW = 4,
                gridH = 2,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "spans",
                        metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_duration"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Request Count",
                widgetType = "stat",
                gridX = 8,
                gridY = 2,
                gridW = 4,
                gridH = 2,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "spans",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "request_count"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Slowest Endpoints",
                widgetType = "toplist",
                gridX = 0,
                gridY = 4,
                gridW = 12,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "spans",
                        metrics = listOf(
                            MetricDef(AggFunction.P95, "duration_ms", "p95"),
                            MetricDef(AggFunction.COUNT, alias = "count")
                        ),
                        groupBy = listOf(GroupByDef("description", GroupByType.FIELD)),
                        orderBy = OrderByDef("p95", "desc"),
                        limit = 10
                    )
                ),
            )
        )
    )

    private fun createLogAnalysisTemplate() = CreateDashboardRequest(
        title = "Log Analysis",
        description = "Log volume, levels, and service breakdown",
        widgets = listOf(
            CreateWidgetRequest(
                title = "Log Volume Over Time",
                widgetType = "timeseries",
                gridX = 0,
                gridY = 0,
                gridW = 12,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "logs",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "log_count")),
                        groupBy = listOf(
                            GroupByDef("timestamp", GroupByType.TIME, "auto"),
                            GroupByDef("level", GroupByType.FIELD)
                        )
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Logs by Level",
                widgetType = "donut",
                gridX = 0,
                gridY = 4,
                gridW = 4,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "logs",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                        groupBy = listOf(GroupByDef("level", GroupByType.FIELD))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Logs by Service",
                widgetType = "bar",
                gridX = 4,
                gridY = 4,
                gridW = 8,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "logs",
                        metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                        groupBy = listOf(GroupByDef("service", GroupByType.FIELD)),
                        orderBy = OrderByDef("count", "desc"),
                        limit = 10
                    )
                ),
            )
        )
    )

    private fun createSystemHealthTemplate() = CreateDashboardRequest(
        title = "System Health",
        description = "Server CPU, memory, disk, and network metrics",
        widgets = listOf(
            CreateWidgetRequest(
                title = "CPU Usage Over Time",
                widgetType = "timeseries",
                gridX = 0,
                gridY = 0,
                gridW = 6,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "metrics",
                        metrics = listOf(MetricDef(AggFunction.AVG, "cpu_percent", "avg_cpu")),
                        groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Memory Usage Over Time",
                widgetType = "timeseries",
                gridX = 6,
                gridY = 0,
                gridW = 6,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "metrics",
                        metrics = listOf(MetricDef(AggFunction.AVG, "mem_used", "avg_mem")),
                        groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Network I/O",
                widgetType = "timeseries",
                gridX = 0,
                gridY = 4,
                gridW = 6,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "metrics",
                        metrics = listOf(
                            MetricDef(AggFunction.AVG, "net_recv_bytes", "avg_recv"),
                            MetricDef(AggFunction.AVG, "net_sent_bytes", "avg_sent")
                        ),
                        groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "auto"))
                    )
                ),
            ),
            CreateWidgetRequest(
                title = "Disk Usage",
                widgetType = "stat",
                gridX = 6,
                gridY = 4,
                gridW = 6,
                gridH = 4,
                queryConfigs = listOf(
                    QueryDsl(
                        dataSource = "metrics",
                        metrics = listOf(
                            MetricDef(AggFunction.MAX, "disk_used", "max_disk_used"),
                            MetricDef(AggFunction.AVG, "load_1", "avg_load")
                        )
                    )
                ),
            )
        )
    )
}
