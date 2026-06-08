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

package com.moneat.overview

import com.moneat.overview.services.OverviewService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverviewServiceTest {

    @Test
    fun `trace summary subquery reads finalized and live trace rollups`() {
        val query = OverviewService().traceSummarySubquery(
            organizationId = -1,
            demoEpochMs = DEMO_EPOCH_MS,
        )

        assertTrue(query.contains("FROM apm_traces_final"))
        assertTrue(query.contains("UNION ALL"))
        assertTrue(query.contains("FROM apm_trace_summaries"))
        assertTrue(query.contains("toInt64(organization_id) IN (-1, -2, -3)"))
        assertTrue(query.contains("toDateTime64(1709312400.000, 3) - INTERVAL 24 HOUR"))
        assertFalse(query.contains("now() - INTERVAL 24 HOUR"))
    }

    @Test
    fun `previous trace summary subquery only reads finalized comparison window`() {
        val query = OverviewService().traceSummarySubquery(
            organizationId = 42,
            demoEpochMs = null,
            previousWindow = true,
        )

        assertTrue(query.contains("FROM apm_traces_final"))
        assertFalse(query.contains("UNION ALL"))
        assertFalse(query.contains("FROM apm_trace_summaries"))
        assertTrue(query.contains("organization_id = 42"))
        assertTrue(query.contains("trace_bucket >= toStartOfHour(now() - INTERVAL 48 HOUR)"))
        assertTrue(query.contains("trace_bucket < toStartOfHour(now() - INTERVAL 24 HOUR)"))
    }

    private companion object {
        private const val DEMO_EPOCH_MS = 1_709_312_400_000L
    }
}
