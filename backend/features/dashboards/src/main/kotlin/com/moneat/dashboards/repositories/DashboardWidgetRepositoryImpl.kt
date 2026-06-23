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

package com.moneat.dashboards.repositories

import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.UpdateWidgetRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class DashboardWidgetRepositoryImpl : DashboardWidgetRepository {
    companion object {
        private const val DEFAULT_WIDGET_WIDTH = 6
        private const val DEFAULT_WIDGET_HEIGHT = 4
    }

    override fun listByDashboardId(dashboardId: Long): List<WidgetData> =
        transaction {
            val dashboardResourceId = Dashboards.selectAll()
                .where { Dashboards.id eq dashboardId }
                .first()[Dashboards.resourceId]
                .toString()

            DashboardWidgets.selectAll()
                .where { DashboardWidgets.dashboardId eq dashboardId }
                .orderBy(DashboardWidgets.sortOrder, SortOrder.ASC)
                .map { row ->
                    WidgetData(
                        id = row[DashboardWidgets.id],
                        resourceId = row[DashboardWidgets.resourceId].toString(),
                        dashboardId = row[DashboardWidgets.dashboardId],
                        dashboardResourceId = dashboardResourceId,
                        title = row[DashboardWidgets.title],
                        widgetType = row[DashboardWidgets.widgetType],
                        gridX = row[DashboardWidgets.gridX],
                        gridY = row[DashboardWidgets.gridY],
                        gridW = row[DashboardWidgets.gridW],
                        gridH = row[DashboardWidgets.gridH],
                        queryConfig = row[DashboardWidgets.queryConfig],
                        queryConfigs = row[DashboardWidgets.queryConfigs],
                        displayConfig = row[DashboardWidgets.displayConfig],
                        sortOrder = row[DashboardWidgets.sortOrder]
                    )
                }
        }

    override fun bulkUpsert(
        dashboardId: Long,
        widgets: List<UpdateWidgetRequest>,
        now: Instant
    ): Set<Long> =
        transaction {
            val existingByResourceId = DashboardWidgets.selectAll()
                .where { DashboardWidgets.dashboardId eq dashboardId }
                .associateBy { it[DashboardWidgets.resourceId].toString() }

            val keptIds = mutableSetOf<Long>()

            widgets.forEachIndexed { index, widget ->
                val requestedId = widget.id
                val existingWidget = requestedId?.let { existingByResourceId[it] }

                if (existingWidget != null) {
                    val numericWidgetId = existingWidget[DashboardWidgets.id]
                    updateWidget(dashboardId, numericWidgetId, widget, index, now)
                    keptIds.add(numericWidgetId)
                } else {
                    keptIds.add(insertWidget(dashboardId, widget, index, now))
                }
            }

            keptIds
        }

    private fun updateWidget(dashboardId: Long, widgetId: Long, widget: UpdateWidgetRequest, index: Int, now: Instant) {
        DashboardWidgets.update({
            (DashboardWidgets.id eq widgetId) and (DashboardWidgets.dashboardId eq dashboardId)
        }) {
            widget.title?.let { v -> it[DashboardWidgets.title] = v }
            widget.widgetType?.let { v -> it[DashboardWidgets.widgetType] = v }
            widget.gridX?.let { v -> it[DashboardWidgets.gridX] = v }
            widget.gridY?.let { v -> it[DashboardWidgets.gridY] = v }
            widget.gridW?.let { v -> it[DashboardWidgets.gridW] = v }
            widget.gridH?.let { v -> it[DashboardWidgets.gridH] = v }
            widget.queryConfigs?.let { qcs ->
                it[DashboardWidgets.queryConfig] =
                    if (qcs.isNotEmpty()) json.encodeToString<QueryDsl>(qcs.first()) else "{}"
                it[DashboardWidgets.queryConfigs] = json.encodeToString<List<QueryDsl>>(qcs)
            }
            widget.displayConfig?.let { dc ->
                it[DashboardWidgets.displayConfig] =
                    if (dc.isEmpty()) "{}" else json.encodeToString<Map<String, String>>(dc)
            }
            it[DashboardWidgets.sortOrder] = widget.sortOrder ?: index
            it[DashboardWidgets.updatedAt] = now
        }
    }

    private fun insertWidget(dashboardId: Long, widget: UpdateWidgetRequest, index: Int, now: Instant): Long =
        DashboardWidgets.insert {
            it[DashboardWidgets.dashboardId] = dashboardId
            it[DashboardWidgets.title] = widget.title
            it[DashboardWidgets.widgetType] = widget.widgetType ?: "timeseries"
            it[DashboardWidgets.gridX] = widget.gridX ?: 0
            it[DashboardWidgets.gridY] = widget.gridY ?: 0
            it[DashboardWidgets.gridW] = widget.gridW ?: DEFAULT_WIDGET_WIDTH
            it[DashboardWidgets.gridH] = widget.gridH ?: DEFAULT_WIDGET_HEIGHT
            it[DashboardWidgets.queryConfig] = widget.queryConfigs
                ?.firstOrNull()
                ?.let { qc -> json.encodeToString<QueryDsl>(qc) }
                ?: "{}"
            it[DashboardWidgets.queryConfigs] = widget.queryConfigs
                ?.let { qcs -> json.encodeToString<List<QueryDsl>>(qcs) }
                ?: "[]"
            it[DashboardWidgets.displayConfig] = widget.displayConfig
                ?.let { dc -> if (dc.isEmpty()) "{}" else json.encodeToString<Map<String, String>>(dc) }
                ?: "{}"
            it[DashboardWidgets.sortOrder] = widget.sortOrder ?: index
            it[DashboardWidgets.createdAt] = now
            it[DashboardWidgets.updatedAt] = now
        } get DashboardWidgets.id

    override fun deleteNotIn(dashboardId: Long, keepIds: Set<Long>) {
        transaction {
            if (keepIds.isEmpty()) {
                DashboardWidgets.deleteWhere { DashboardWidgets.dashboardId eq dashboardId }
            } else {
                DashboardWidgets.deleteWhere {
                    (DashboardWidgets.dashboardId eq dashboardId) and
                        (DashboardWidgets.id notInList keepIds.toList())
                }
            }
        }
    }

    override fun deleteById(dashboardId: Long, widgetId: Long): Boolean =
        transaction {
            DashboardWidgets.deleteWhere {
                (DashboardWidgets.dashboardId eq dashboardId) and
                    (DashboardWidgets.id eq widgetId)
            } > 0
        }

    override fun insert(dashboardId: Long, widget: CreateWidgetRequest, sortOrder: Int, now: Instant): Long =
        transaction {
            DashboardWidgets.insert {
                it[DashboardWidgets.dashboardId] = dashboardId
                it[DashboardWidgets.title] = widget.title
                it[DashboardWidgets.widgetType] = widget.widgetType
                it[DashboardWidgets.gridX] = widget.gridX
                it[DashboardWidgets.gridY] = widget.gridY
                it[DashboardWidgets.gridW] = widget.gridW
                it[DashboardWidgets.gridH] = widget.gridH
                it[DashboardWidgets.queryConfig] = if (widget.queryConfigs.isNotEmpty()) {
                    json.encodeToString<QueryDsl>(widget.queryConfigs.first())
                } else {
                    "{}"
                }
                it[DashboardWidgets.queryConfigs] = json.encodeToString<List<QueryDsl>>(widget.queryConfigs)
                it[DashboardWidgets.displayConfig] = if (widget.displayConfig.isEmpty()) {
                    "{}"
                } else {
                    json.encodeToString<Map<String, String>>(widget.displayConfig)
                }
                it[DashboardWidgets.sortOrder] = sortOrder
                it[DashboardWidgets.createdAt] = now
                it[DashboardWidgets.updatedAt] = now
            } get DashboardWidgets.id
        }
}
