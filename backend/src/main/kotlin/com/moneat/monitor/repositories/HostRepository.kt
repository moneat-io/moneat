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

package com.moneat.monitor.repositories

import com.moneat.monitor.models.HostData
import kotlin.uuid.Uuid

/**
 * Repository for host data access.
 * Abstracts PostgreSQL (Hosts table) and ClickHouse infra queries.
 */
interface HostRepository {
    fun getHostCountForOrganization(organizationId: Int): Int
    fun listByOrganizationId(organizationId: Int): List<HostData>
    fun getById(hostId: Int): HostData?
    fun getByResourceId(resourceId: Uuid, organizationIds: List<Int>): HostData?
    fun delete(hostId: Int, organizationId: Int): Boolean
    suspend fun executeClickHouseQuery(sql: String): String
    suspend fun deleteClickHouseData(sql: String): Boolean
}
