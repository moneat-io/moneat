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

import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.UpdateDashboardRequest

/**
 * Repository for dashboard data access.
 */
interface DashboardRepository {
    fun list(orgId: Long, projectId: Long? = null, userId: Int? = null): List<DashboardWithFavoriteFlag>
    fun getById(id: Long, orgId: Long, userId: Int? = null): DashboardWithFavoriteFlag?
    fun create(
        orgId: Long,
        userId: Long,
        request: CreateDashboardRequest,
        projectId: Long? = null,
        folderId: Long? = null,
    ): CreatedDashboardData
    fun update(id: Long, orgId: Long, request: UpdateDashboardRequest, folderId: Long? = null): Boolean
    fun moveToFolder(id: Long, orgId: Long, folderId: Long?): Boolean
    fun delete(id: Long, orgId: Long): Boolean

    /**
     * Marks [id] as the org's single default ("home") dashboard, clearing the flag on
     * every other dashboard in the org. Returns false when [id] is not in the org.
     */
    fun setDefault(id: Long, orgId: Long): Boolean
    fun toggleFavorite(userId: Int, dashboardId: Long, orgId: Long): Boolean
    fun search(orgId: Long, userId: Int?, pattern: String): List<DashboardWithFavoriteFlag>
}

data class DashboardWithFavoriteFlag(
    val id: Long,
    val resourceId: String = id.toString(),
    val orgId: Long,
    val projectId: Long? = null,
    val projectResourceId: String? = projectId?.toString(),
    val folderId: Long? = null,
    val folderResourceId: String? = folderId?.toString(),
    val title: String,
    val description: String?,
    val layoutType: String,
    val isDefault: Boolean,
    val isFavorited: Boolean,
    val variables: String,
    val createdBy: Long,
    val createdAt: String,
    val updatedAt: String,
    val ownerName: String? = null
)

data class CreatedDashboardData(
    val id: Long,
    val resourceId: String = id.toString(),
    val orgId: Long,
    val projectId: Long? = null,
    val projectResourceId: String? = projectId?.toString(),
    val folderId: Long? = null,
    val folderResourceId: String? = folderId?.toString(),
    val title: String,
    val description: String?,
    val layoutType: String,
    val isDefault: Boolean,
    val variables: String,
    val createdBy: Long,
    val createdAt: String,
    val updatedAt: String,
    val ownerName: String? = null
)
