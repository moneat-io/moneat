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

package com.moneat.uptime.services

import com.moneat.billing.services.BillingQuotaService
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.repositories.UptimeMonitorRepository
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class UptimeRetryValidationTest {
    private val service = UptimeService(BillingQuotaService(), mockk<UptimeMonitorRepository>(relaxed = true))

    @Test
    fun `rejects unsafe retry settings for create and update`() {
        assertFailsWith<IllegalArgumentException> {
            service.createMonitor(
                organizationId = 1,
                request = CreateUptimeMonitorRequest(name = "Too many retries", type = "http", retries = 11),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createMonitor(
                organizationId = 1,
                request = CreateUptimeMonitorRequest(
                    name = "Tight retry loop",
                    type = "http",
                    retryIntervalSeconds = 0,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateMonitor(
                monitorId = UUID.randomUUID(),
                organizationId = 1,
                request = UpdateUptimeMonitorRequest(retries = -1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateMonitor(
                monitorId = UUID.randomUUID(),
                organizationId = 1,
                request = UpdateUptimeMonitorRequest(retryIntervalSeconds = 3601),
            )
        }
    }
}
