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

package com.moneat.billing.services

import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import kotlin.test.Test

class BillingQuotaServiceBatchTest {
    @Test
    fun `refundUnitsBatch delegates each count and byte quota type`() {
        val service = spyk(BillingQuotaService())
        every { service.refundUnits(any(), any(), any(), any()) } just runs

        service.refundUnitsBatch(
            organizationId = 7,
            requestedUnitsByType = mapOf("error" to 3),
            requestedBytesByType = mapOf("error" to 30, "log" to 20),
        )

        verify(exactly = 1) { service.refundUnits(7, 3, "error", 30) }
        verify(exactly = 1) { service.refundUnits(7, 0, "log", 20) }
    }
}
