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

package com.moneat.uptime.repositories

import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import java.util.UUID

/**
 * Repository for uptime monitor data access.
 * Abstracts PostgreSQL (UptimeMonitors) and ClickHouse (uptime_heartbeats) queries.
 */
interface UptimeMonitorRepository {
    fun getMonitorCountForOrganization(organizationId: Int): Int
    fun existsById(monitorId: UUID): Boolean
    fun create(orgId: Int, request: CreateUptimeMonitorRequest, pushToken: String?): UUID
    fun update(monitorId: UUID, orgId: Int, request: UpdateUptimeMonitorRequest): Boolean
    fun delete(monitorId: UUID, orgId: Int): Boolean
    fun listByOrganizationId(orgId: Int): List<UptimeMonitorData>
    fun getByIdAndOrg(monitorId: UUID, orgId: Int): UptimeMonitorData?
    fun pause(monitorId: UUID, orgId: Int): Boolean
    fun resume(monitorId: UUID, orgId: Int): Boolean
    fun getMonitorsDueForCheck(): List<UptimeMonitorData>
    fun updateStatus(monitorId: UUID, result: CheckResult): Boolean
    fun getByPushToken(token: String): UptimeMonitorData?
    fun getOrganizationTier(orgId: Int): String
    suspend fun executeClickHouseQuery(sql: String): String
    suspend fun executeClickHouseInsert(sql: String): Boolean
}
