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

import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.UpdateDashboardRequest
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class CustomDashboardService {

    fun listDashboards(orgId: Long, projectId: Long? = null): List<DashboardResponse> {
        return transaction {
            val query = Dashboards.selectAll().where {
                if (projectId != null) {
                    (Dashboards.orgId eq orgId) and (Dashboards.projectId eq projectId)
                } else {
                    Dashboards.orgId eq orgId
                }
            }.orderBy(Dashboards.updatedAt, SortOrder.DESC)

            query.map { row ->
                val dashboardId = row[Dashboards.id]
                val widgets = DashboardWidgets.selectAll().where {
                    DashboardWidgets.dashboardId eq dashboardId
                }.orderBy(DashboardWidgets.sortOrder, SortOrder.ASC).map { wr ->
                    val queryConfigs: List<QueryDsl> = try {
                        json.decodeFromString(wr[DashboardWidgets.queryConfigs])
                    } catch (_: Exception) {
                        try {
                            listOf(json.decodeFromString<QueryDsl>(wr[DashboardWidgets.queryConfig]))
                        } catch (_: Exception) { emptyList() }
                    }
                    WidgetResponse(
                        id = wr[DashboardWidgets.id],
                        dashboardId = wr[DashboardWidgets.dashboardId],
                        title = wr[DashboardWidgets.title],
                        widgetType = wr[DashboardWidgets.widgetType],
                        gridX = wr[DashboardWidgets.gridX],
                        gridY = wr[DashboardWidgets.gridY],
                        gridW = wr[DashboardWidgets.gridW],
                        gridH = wr[DashboardWidgets.gridH],
                        queryConfigs = queryConfigs,
                        displayConfig = try {
                            json.decodeFromString(wr[DashboardWidgets.displayConfig])
                        } catch (_: Exception) { emptyMap() },
                        sortOrder = wr[DashboardWidgets.sortOrder]
                    )
                }

                DashboardResponse(
                    id = dashboardId,
                    orgId = row[Dashboards.orgId],
                    projectId = row[Dashboards.projectId],
                    title = row[Dashboards.title],
                    description = row[Dashboards.description],
                    layoutType = row[Dashboards.layoutType],
                    isDefault = row[Dashboards.isDefault],
                    createdBy = row[Dashboards.createdBy],
                    createdAt = row[Dashboards.createdAt].toString(),
                    updatedAt = row[Dashboards.updatedAt].toString(),
                    widgets = widgets
                )
            }
        }
    }

    fun getDashboard(id: Long, orgId: Long): DashboardResponse? {
        return transaction {
            val row = Dashboards.selectAll().where {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }.firstOrNull() ?: return@transaction null

            val widgets = DashboardWidgets.selectAll().where {
                DashboardWidgets.dashboardId eq id
            }.orderBy(DashboardWidgets.sortOrder, SortOrder.ASC).map { wr ->
                val queryConfigs: List<QueryDsl> = try {
                    json.decodeFromString(wr[DashboardWidgets.queryConfigs])
                } catch (_: Exception) {
                    // Fallback: wrap legacy single query_config in a list
                    try {
                        listOf(json.decodeFromString<QueryDsl>(wr[DashboardWidgets.queryConfig]))
                    } catch (_: Exception) { emptyList() }
                }
                WidgetResponse(
                    id = wr[DashboardWidgets.id],
                    dashboardId = wr[DashboardWidgets.dashboardId],
                    title = wr[DashboardWidgets.title],
                    widgetType = wr[DashboardWidgets.widgetType],
                    gridX = wr[DashboardWidgets.gridX],
                    gridY = wr[DashboardWidgets.gridY],
                    gridW = wr[DashboardWidgets.gridW],
                    gridH = wr[DashboardWidgets.gridH],
                    queryConfigs = queryConfigs,
                    displayConfig = try {
                        json.decodeFromString(wr[DashboardWidgets.displayConfig])
                    } catch (_: Exception) { emptyMap() },
                    sortOrder = wr[DashboardWidgets.sortOrder]
                )
            }

            DashboardResponse(
                id = row[Dashboards.id],
                orgId = row[Dashboards.orgId],
                projectId = row[Dashboards.projectId],
                title = row[Dashboards.title],
                description = row[Dashboards.description],
                layoutType = row[Dashboards.layoutType],
                isDefault = row[Dashboards.isDefault],
                createdBy = row[Dashboards.createdBy],
                createdAt = row[Dashboards.createdAt].toString(),
                updatedAt = row[Dashboards.updatedAt].toString(),
                widgets = widgets
            )
        }
    }

    fun createDashboard(orgId: Long, userId: Long, request: CreateDashboardRequest): DashboardResponse {
        return transaction {
            val now = Clock.System.now()
            val dashboardId = Dashboards.insert {
                it[Dashboards.orgId] = orgId
                it[projectId] = request.projectId
                it[title] = request.title
                it[description] = request.description
                it[layoutType] = request.layoutType
                it[isDefault] = request.isDefault
                it[createdBy] = userId
                it[createdAt] = now
                it[updatedAt] = now
            } get Dashboards.id

            val widgets = request.widgets.mapIndexed { index, widget ->
                val widgetId = DashboardWidgets.insert {
                    it[DashboardWidgets.dashboardId] = dashboardId
                    it[title] = widget.title
                    it[widgetType] = widget.widgetType
                    it[gridX] = widget.gridX
                    it[gridY] = widget.gridY
                    it[gridW] = widget.gridW
                    it[gridH] = widget.gridH
                    it[queryConfig] = if (widget.queryConfigs.isNotEmpty()) {
                        json.encodeToString(widget.queryConfigs.first())
                    } else {
                        "{}"
                    }
                    it[queryConfigs] = json.encodeToString(widget.queryConfigs)
                    it[displayConfig] = if (widget.displayConfig.isEmpty()) {
                        "{}"
                    } else {
                        json.encodeToString(widget.displayConfig)
                    }
                    it[sortOrder] = widget.sortOrder.takeIf { so -> so > 0 } ?: index
                    it[createdAt] = now
                    it[updatedAt] = now
                } get DashboardWidgets.id

                WidgetResponse(
                    id = widgetId,
                    dashboardId = dashboardId,
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

            DashboardResponse(
                id = dashboardId,
                orgId = orgId,
                projectId = request.projectId,
                title = request.title,
                description = request.description,
                layoutType = request.layoutType,
                isDefault = request.isDefault,
                createdBy = userId,
                createdAt = now.toString(),
                updatedAt = now.toString(),
                widgets = widgets
            )
        }
    }

    fun updateDashboard(id: Long, orgId: Long, request: UpdateDashboardRequest): DashboardResponse? {
        return transaction {
            val existing = Dashboards.selectAll().where {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }.firstOrNull() ?: return@transaction null

            val now = Clock.System.now()
            Dashboards.update({ (Dashboards.id eq id) and (Dashboards.orgId eq orgId) }) {
                request.title?.let { t -> it[title] = t }
                request.description?.let { d -> it[description] = d }
                request.layoutType?.let { lt -> it[layoutType] = lt }
                request.isDefault?.let { d -> it[isDefault] = d }
                it[updatedAt] = now
            }

            // Replace all widgets if provided
            if (request.widgets != null) {
                DashboardWidgets.deleteWhere { dashboardId eq id }
                request.widgets.forEachIndexed { index, widget ->
                    DashboardWidgets.insert {
                        it[dashboardId] = id
                        it[title] = widget.title
                        it[widgetType] = widget.widgetType ?: "timeseries"
                        it[gridX] = widget.gridX ?: 0
                        it[gridY] = widget.gridY ?: 0
                        it[gridW] = widget.gridW ?: 6
                        it[gridH] = widget.gridH ?: 4
                        it[queryConfig] = widget.queryConfigs?.firstOrNull()?.let { qc ->
                            json.encodeToString(qc)
                        } ?: "{}"
                        it[queryConfigs] = widget.queryConfigs?.let { qcs -> json.encodeToString(qcs) } ?: "[]"
                        it[displayConfig] = widget.displayConfig?.let { dc -> json.encodeToString(dc) } ?: "{}"
                        it[sortOrder] = widget.sortOrder ?: index
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }

            getDashboard(id, orgId)
        }
    }

    fun deleteDashboard(id: Long, orgId: Long): Boolean {
        return transaction {
            val deleted = Dashboards.deleteWhere {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }
            deleted > 0
        }
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
                        groupBy = listOf(GroupByDef("transaction", GroupByType.FIELD)),
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
                        dataSource = "system_metrics",
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
                        dataSource = "system_metrics",
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
                        dataSource = "system_metrics",
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
                        dataSource = "system_metrics",
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
