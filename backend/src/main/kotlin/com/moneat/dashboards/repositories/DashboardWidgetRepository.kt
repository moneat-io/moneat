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
import com.moneat.dashboards.models.UpdateWidgetRequest
import kotlin.time.Instant

/**
 * Repository for dashboard widget data access.
 */
interface DashboardWidgetRepository {
    fun listByDashboardId(dashboardId: Long): List<WidgetData>
    fun bulkUpsert(dashboardId: Long, widgets: List<UpdateWidgetRequest>, now: Instant): Set<Long>
    fun deleteNotIn(dashboardId: Long, keepIds: Set<Long>)
    fun deleteById(dashboardId: Long, widgetId: Long): Boolean
    fun insert(dashboardId: Long, widget: CreateWidgetRequest, sortOrder: Int, now: Instant): Long
}

data class WidgetData(
    val id: Long,
    val dashboardId: Long,
    val title: String?,
    val widgetType: String,
    val gridX: Int,
    val gridY: Int,
    val gridW: Int,
    val gridH: Int,
    val queryConfig: String,
    val queryConfigs: String,
    val displayConfig: String,
    val sortOrder: Int
)
