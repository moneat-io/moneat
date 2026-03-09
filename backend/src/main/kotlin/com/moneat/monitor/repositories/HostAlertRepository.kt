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

import com.moneat.monitor.models.AlertRow
import com.moneat.monitor.models.AlertSettingRow
import com.moneat.monitor.models.CreateAlertData
import com.moneat.monitor.models.UpdateAlertData

/**
 * Repository for host alert data access.
 * Abstracts HostAlerts, HostAlertSettings, and OrganizationAlertTemplates tables.
 */
interface HostAlertRepository {
    fun listByHostAndOrg(hostId: Int, organizationId: Int): List<AlertRow>
    fun getAlertConfig(hostId: Int, organizationId: Int, metricName: String): AlertRow?
    fun getAlertSettings(hostId: Int): List<AlertSettingRow>
    fun upsertAlertSettings(hostId: Int, organizationId: Int, scope: String)
    fun createAlert(alert: CreateAlertData): Long
    fun updateAlert(alertId: Long, hostId: Int, organizationId: Int, data: UpdateAlertData, scope: String): Boolean
    fun deleteAlert(alertId: Long, hostId: Int, organizationId: Int, scope: String): Boolean
    fun listGlobalAlertsForHost(organizationId: Int, hostId: Int): List<AlertRow>
    fun findAlertById(alertId: Long): AlertRow?
}
