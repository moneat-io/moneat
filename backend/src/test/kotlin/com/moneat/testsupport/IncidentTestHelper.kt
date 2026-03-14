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

package com.moneat.testsupport

import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.services.IncidentProvider
import com.moneat.incident.services.IncidentProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Shared helpers for IncidentService tests to reduce duplication of
 * provider mock setup and event log fetching.
 */
object IncidentTestHelper {

    /**
     * Creates and registers a mock IncidentProvider. Returns the provider for
     * any additional stubbing.
     */
    fun registerMockProvider(
        providerType: String,
        sendAlertResult: Result<String>? = null,
        sendAlertThrows: Throwable? = null,
        resolveAlertResult: Result<String>? = null,
        resolveAlertThrows: Throwable? = null
    ): IncidentProvider {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns providerType
        sendAlertResult?.let { coEvery { provider.sendAlert(any(), any()) } returns it }
        sendAlertThrows?.let { coEvery { provider.sendAlert(any(), any()) } throws it }
        resolveAlertResult?.let { coEvery { provider.resolveAlert(any(), any()) } returns it }
        resolveAlertThrows?.let { coEvery { provider.resolveAlert(any(), any()) } throws it }
        IncidentProviderRegistry.register(provider)
        return provider
    }

    /**
     * Fetches IncidentEventLog rows for the given organization.
     */
    fun getEventLogs(orgId: Int): List<ResultRow> = transaction {
        IncidentEventLog.selectAll()
            .where { IncidentEventLog.organizationId eq orgId }
            .toList()
    }

    /**
     * Counts IncidentEventLog rows for the given organization.
     */
    fun getEventLogCount(orgId: Int): Long = transaction {
        IncidentEventLog.selectAll()
            .where { IncidentEventLog.organizationId eq orgId }
            .count()
    }
}
