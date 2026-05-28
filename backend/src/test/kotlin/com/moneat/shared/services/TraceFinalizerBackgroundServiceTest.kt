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

package com.moneat.shared.services

import com.moneat.config.ClickHouseClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class TraceFinalizerBackgroundServiceTest {
    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "test_db"
        every { ClickHouseClient.isInitialized() } returns true
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    @Test
    fun `finalizeRecent finalizes summaries into apm_traces_final off the read path`() = runBlocking {
        val captured = mutableListOf<String>()
        coEvery { ClickHouseClient.executeLongRunning(any(), any()) } coAnswers {
            captured.add(firstArg())
        }

        TraceFinalizerBackgroundService().finalizeRecent(emitHours = 2)

        assertTrue(captured.size == 1, "expected one finalize INSERT, got ${captured.size}")
        val sql = captured.single()
        // Writes finalized rows into apm_traces_final from the summaries rollup.
        assertTrue(sql.contains("INSERT INTO `test_db`.apm_traces_final"))
        assertTrue(sql.contains("`test_db`.apm_trace_summaries"))
        // The heavy argMin aggregation runs here (off the dashboard read path).
        assertTrue(sql.contains("argMinMerge(root_service_state)"))
        // emit window = 2h, scan window = emit + lookback (2 + 2 = 4h).
        assertTrue(sql.contains("toStartOfHour(trace_start) >= toStartOfHour(now() - INTERVAL 2 HOUR)"))
        assertTrue(sql.contains("bucket_start >= toStartOfHour(now() - INTERVAL 4 HOUR)"))
        assertTrue(sql.contains("GROUP BY organization_id, trace_id_canonical"))
    }

    @Test
    fun `start runs a finalization pass and stop cancels the loop`() = runBlocking {
        val firstRun = CompletableDeferred<String>()
        coEvery { ClickHouseClient.executeLongRunning(any(), any()) } coAnswers {
            if (!firstRun.isCompleted) firstRun.complete(firstArg())
        }

        val service = TraceFinalizerBackgroundService()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            service.start(scope)
            // The first pass after startup finalizes the wider backfill window.
            val sql = withTimeout(5_000) { firstRun.await() }
            assertTrue(sql.contains("apm_traces_final"))
        } finally {
            service.stop()
            scope.cancel()
        }
    }
}
