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
import com.moneat.dashboards.models.DashboardFavorites
import com.moneat.dashboards.models.DashboardFolders
import com.moneat.dashboards.models.DashboardResponse
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
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
import com.moneat.shared.models.Projects
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class CustomDashboardService {

    private fun parseVariables(variablesJson: String): List<DashboardVariable> {
        return try {
            json.decodeFromString<List<DashboardVariable>>(variablesJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun listDashboards(orgId: Long, projectId: Long? = null, userId: Int? = null): List<DashboardResponse> {
        return transaction {
            val query = Dashboards.selectAll().where {
                if (projectId != null) {
                    (Dashboards.orgId eq orgId) and (Dashboards.projectId eq projectId)
                } else {
                    Dashboards.orgId eq orgId
                }
            }.orderBy(Dashboards.updatedAt, SortOrder.DESC)

            val favoritedIds = userId?.let { uid ->
                DashboardFavorites.selectAll()
                    .where { DashboardFavorites.userId eq uid }
                    .map { it[DashboardFavorites.dashboardId] }
                    .toSet()
            } ?: emptySet()

            query.map { row ->
                val dashboardId = row[Dashboards.id]
                val widgets = loadWidgets(dashboardId)
                DashboardResponse(
                    id = dashboardId,
                    orgId = row[Dashboards.orgId],
                    projectId = row[Dashboards.projectId],
                    folderId = row[Dashboards.folderId],
                    title = row[Dashboards.title],
                    description = row[Dashboards.description],
                    layoutType = row[Dashboards.layoutType],
                    isDefault = row[Dashboards.isDefault],
                    isFavorited = dashboardId in favoritedIds,
                    variables = parseVariables(row[Dashboards.variables]),
                    createdBy = row[Dashboards.createdBy],
                    createdAt = row[Dashboards.createdAt].toString(),
                    updatedAt = row[Dashboards.updatedAt].toString(),
                    widgets = widgets
                )
            }
        }
    }

    private fun loadWidgets(dashboardId: Long): List<WidgetResponse> =
        DashboardWidgets.selectAll()
            .where { DashboardWidgets.dashboardId eq dashboardId }
            .orderBy(DashboardWidgets.sortOrder, SortOrder.ASC)
            .map { wr ->
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
                    } catch (_: Exception) {
                        emptyMap()
                    },
                    sortOrder = wr[DashboardWidgets.sortOrder]
                )
            }

    fun getDashboard(id: Long, orgId: Long, userId: Int? = null): DashboardResponse? {
        return transaction {
            val row = Dashboards.selectAll().where {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }.firstOrNull() ?: return@transaction null

            val isFavorited = userId?.let { uid ->
                DashboardFavorites.selectAll()
                    .where {
                        (DashboardFavorites.userId eq uid) and (DashboardFavorites.dashboardId eq id)
                    }
                    .any()
            } ?: false

            val widgets = loadWidgets(id)

            DashboardResponse(
                id = row[Dashboards.id],
                orgId = row[Dashboards.orgId],
                projectId = row[Dashboards.projectId],
                folderId = row[Dashboards.folderId],
                title = row[Dashboards.title],
                description = row[Dashboards.description],
                layoutType = row[Dashboards.layoutType],
                isDefault = row[Dashboards.isDefault],
                isFavorited = isFavorited,
                variables = parseVariables(row[Dashboards.variables]),
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
                it[Dashboards.projectId] = request.projectId
                it[Dashboards.folderId] = request.folderId
                it[Dashboards.title] = request.title
                it[Dashboards.description] = request.description
                it[Dashboards.layoutType] = request.layoutType
                it[Dashboards.isDefault] = request.isDefault
                it[Dashboards.variables] = json.encodeToString(request.variables)
                it[Dashboards.createdBy] = userId
                it[Dashboards.createdAt] = now
                it[Dashboards.updatedAt] = now
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
                variables = request.variables,
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
                request.title?.let { t -> it[Dashboards.title] = t }
                request.description?.let { d -> it[Dashboards.description] = d }
                request.folderId?.let { fid -> it[Dashboards.folderId] = fid }
                request.layoutType?.let { lt -> it[Dashboards.layoutType] = lt }
                request.isDefault?.let { d -> it[Dashboards.isDefault] = d }
                request.variables?.let { v -> it[Dashboards.variables] = json.encodeToString(v) }
                it[Dashboards.updatedAt] = now
            }

            // Update widgets in place to preserve IDs and dependent records (e.g. alerts)
            if (request.widgets != null) {
                val existingById = DashboardWidgets.selectAll()
                    .where { DashboardWidgets.dashboardId eq id }
                    .associateBy { it[DashboardWidgets.id] }

                val keptIds = mutableSetOf<Long>()

                request.widgets.forEachIndexed { index, widget ->
                    val requestedId = widget.id
                    val existingWidget = requestedId?.let { existingById[it] }

                    if (existingWidget != null) {
                        DashboardWidgets.update({
                            (DashboardWidgets.id eq requestedId) and (DashboardWidgets.dashboardId eq id)
                        }) {
                            widget.title?.let { v -> it[title] = v }
                            widget.widgetType?.let { v -> it[widgetType] = v }
                            widget.gridX?.let { v -> it[gridX] = v }
                            widget.gridY?.let { v -> it[gridY] = v }
                            widget.gridW?.let { v -> it[gridW] = v }
                            widget.gridH?.let { v -> it[gridH] = v }
                            widget.queryConfigs?.let { qcs ->
                                it[queryConfig] = if (qcs.isNotEmpty()) json.encodeToString(qcs.first()) else "{}"
                                it[queryConfigs] = json.encodeToString(qcs)
                            }
                            widget.displayConfig?.let { dc ->
                                it[displayConfig] = if (dc.isEmpty()) "{}" else json.encodeToString(dc)
                            }
                            widget.sortOrder?.let { v -> it[sortOrder] = v } ?: run { it[sortOrder] = index }
                            it[updatedAt] = now
                        }
                        keptIds.add(requestedId)
                    } else {
                        val newId = DashboardWidgets.insert {
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
                            it[displayConfig] = widget.displayConfig?.let { dc ->
                                if (dc.isEmpty()) "{}" else json.encodeToString(dc)
                            } ?: "{}"
                            it[sortOrder] = widget.sortOrder ?: index
                            it[createdAt] = now
                            it[updatedAt] = now
                        } get DashboardWidgets.id
                        keptIds.add(newId)
                    }
                }

                if (keptIds.isEmpty()) {
                    DashboardWidgets.deleteWhere { dashboardId eq id }
                } else {
                    DashboardWidgets.deleteWhere {
                        (dashboardId eq id) and (DashboardWidgets.id notInList keptIds.toList())
                    }
                }
            }

            getDashboard(id, orgId, null)
        }
    }

    fun moveDashboardToFolder(id: Long, orgId: Long, folderId: Long?): Boolean {
        return transaction {
            val updated = Dashboards.update({ (Dashboards.id eq id) and (Dashboards.orgId eq orgId) }) {
                it[Dashboards.folderId] = folderId
                it[Dashboards.updatedAt] = Clock.System.now()
            }
            updated > 0
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

    fun listFolders(orgId: Long): List<FolderResponse> {
        return transaction {
            DashboardFolders.selectAll()
                .where { DashboardFolders.orgId eq orgId }
                .orderBy(DashboardFolders.sortOrder, SortOrder.ASC)
                .map { row ->
                    FolderResponse(
                        id = row[DashboardFolders.id],
                        orgId = row[DashboardFolders.orgId],
                        name = row[DashboardFolders.name],
                        color = row[DashboardFolders.color],
                        sortOrder = row[DashboardFolders.sortOrder],
                        createdAt = row[DashboardFolders.createdAt].toString(),
                        updatedAt = row[DashboardFolders.updatedAt].toString()
                    )
                }
        }
    }

    fun createFolder(orgId: Long, request: CreateFolderRequest): FolderResponse {
        return transaction {
            val now = Clock.System.now()
            val id = DashboardFolders.insert {
                it[DashboardFolders.orgId] = orgId
                it[DashboardFolders.name] = request.name
                it[DashboardFolders.color] = request.color
                it[DashboardFolders.sortOrder] = request.sortOrder
                it[DashboardFolders.createdAt] = now
                it[DashboardFolders.updatedAt] = now
            } get DashboardFolders.id

            FolderResponse(
                id = id,
                orgId = orgId,
                name = request.name,
                color = request.color,
                sortOrder = request.sortOrder,
                createdAt = now.toString(),
                updatedAt = now.toString()
            )
        }
    }

    fun updateFolder(id: Long, orgId: Long, request: UpdateFolderRequest): FolderResponse? {
        return transaction {
            val row = DashboardFolders.selectAll().where {
                (DashboardFolders.id eq id) and (DashboardFolders.orgId eq orgId)
            }.firstOrNull() ?: return@transaction null

            val now = Clock.System.now()
            DashboardFolders.update({ (DashboardFolders.id eq id) and (DashboardFolders.orgId eq orgId) }) {
                request.name?.let { n -> it[DashboardFolders.name] = n }
                request.color?.let { c -> it[DashboardFolders.color] = c }
                request.sortOrder?.let { so -> it[DashboardFolders.sortOrder] = so }
                it[DashboardFolders.updatedAt] = now
            }

            FolderResponse(
                id = id,
                orgId = row[DashboardFolders.orgId],
                name = request.name ?: row[DashboardFolders.name],
                color = request.color ?: row[DashboardFolders.color],
                sortOrder = request.sortOrder ?: row[DashboardFolders.sortOrder],
                createdAt = row[DashboardFolders.createdAt].toString(),
                updatedAt = now.toString()
            )
        }
    }

    fun deleteFolder(id: Long, orgId: Long): Boolean {
        return transaction {
            val deleted = DashboardFolders.deleteWhere {
                (DashboardFolders.id eq id) and (DashboardFolders.orgId eq orgId)
            }
            deleted > 0
        }
    }

    fun toggleFavorite(userId: Int, dashboardId: Long, orgId: Long): Boolean {
        return transaction {
            val exists = Dashboards.selectAll().where {
                (Dashboards.id eq dashboardId) and (Dashboards.orgId eq orgId)
            }.any()
            if (!exists) return@transaction false

            val alreadyFavorited = DashboardFavorites.selectAll().where {
                (DashboardFavorites.userId eq userId) and (DashboardFavorites.dashboardId eq dashboardId)
            }.any()

            if (alreadyFavorited) {
                DashboardFavorites.deleteWhere {
                    (DashboardFavorites.userId eq userId) and (DashboardFavorites.dashboardId eq dashboardId)
                }
                false
            } else {
                val now = Clock.System.now()
                DashboardFavorites.insert {
                    it[DashboardFavorites.userId] = userId
                    it[DashboardFavorites.dashboardId] = dashboardId
                    it[DashboardFavorites.createdAt] = now
                }
                true
            }
        }
    }

    fun search(orgId: Long, userId: Int?, query: String): SearchResponse {
        if (query.isBlank()) return SearchResponse()
        val pattern = "%${query.trim().lowercase()}%"
        return transaction {
            val dashboards = Dashboards.selectAll()
                .where {
                    (Dashboards.orgId eq orgId) and (
                        (Dashboards.title.lowerCase() like pattern) or
                            ((Dashboards.description.isNotNull()) and (Dashboards.description.lowerCase() like pattern))
                        )
                }
                .orderBy(Dashboards.updatedAt, SortOrder.DESC)
                .limit(10)
                .map { row ->
                    val did = row[Dashboards.id]
                    val isFav = userId?.let { uid ->
                        DashboardFavorites.selectAll()
                            .where {
                                (DashboardFavorites.userId eq uid) and (DashboardFavorites.dashboardId eq did)
                            }
                            .any()
                    } ?: false
                    DashboardResponse(
                        id = did,
                        orgId = row[Dashboards.orgId],
                        projectId = row[Dashboards.projectId],
                        folderId = row[Dashboards.folderId],
                        title = row[Dashboards.title],
                        description = row[Dashboards.description],
                        layoutType = row[Dashboards.layoutType],
                        isDefault = row[Dashboards.isDefault],
                        isFavorited = isFav,
                        variables = parseVariables(row[Dashboards.variables]),
                        createdBy = row[Dashboards.createdBy],
                        createdAt = row[Dashboards.createdAt].toString(),
                        updatedAt = row[Dashboards.updatedAt].toString(),
                        widgets = loadWidgets(did)
                    )
                }

            val projects = Projects.selectAll()
                .where {
                    (Projects.organization_id eq orgId.toInt()) and (Projects.name.lowerCase() like pattern)
                }
                .limit(10)
                .map { row ->
                    SearchProjectResponse(
                        id = row[Projects.id],
                        name = row[Projects.name]
                    )
                }

            SearchResponse(dashboards = dashboards, projects = projects)
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
