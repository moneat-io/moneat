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

import com.moneat.dashboards.repositories.models.DashboardFolderRow

/**
 * Repository for dashboard folder data access.
 */
interface DashboardFolderRepository {
    fun listByOrgId(orgId: Long): List<DashboardFolderRow>
    fun getByIdAndOrgId(id: Long, orgId: Long): DashboardFolderRow?
    fun create(orgId: Long, name: String, color: String?, sortOrder: Int): Long
    fun update(id: Long, orgId: Long, name: String?, color: String?, sortOrder: Int?)
    fun delete(id: Long, orgId: Long): Int
}
