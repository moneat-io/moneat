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
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TraceFinalizerBackgroundServiceTest {
    private val captured = mutableListOf<String>()

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "test_db"
        every { ClickHouseClient.isInitialized() } returns true
        captured.clear()
        coEvery { ClickHouseClient.executeLongRunning(any(), any()) } coAnswers { captured.add(firstArg()) }
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    private fun service(
        enabled: Boolean = true,
        intervalSeconds: Long = 600,
        refreshWindowHours: Int = 2,
        backfillWindowHours: Int = 50,
        lookbackHours: Int = 2,
        maxExecutionSeconds: Int = 540,
    ) = TraceFinalizerBackgroundService(
        enabled = enabled,
        intervalSeconds = intervalSeconds,
        refreshWindowHours = refreshWindowHours,
        backfillWindowHours = backfillWindowHours,
        lookbackHours = lookbackHours,
        maxExecutionSeconds = maxExecutionSeconds,
    )

    @Test
    fun `finalizeRecent finalizes summaries into apm_traces_final off the read path`() = runBlocking {
        service().finalizeRecent(emitHours = 2)

        assertEquals(1, captured.size, "expected one finalize INSERT")
        val sql = captured.single()
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
    fun `runOnce backfills the wide window first then refreshes the recent window`() = runBlocking {
        val svc = service(refreshWindowHours = 2, backfillWindowHours = 50, lookbackHours = 2)

        svc.runOnce()
        svc.runOnce()

        assertEquals(2, captured.size)
        // First pass: wider backfill window (covers history + downtime gaps).
        assertTrue(captured[0].contains("toStartOfHour(trace_start) >= toStartOfHour(now() - INTERVAL 50 HOUR)"))
        // Subsequent passes: only the recent refresh window.
        assertTrue(captured[1].contains("toStartOfHour(trace_start) >= toStartOfHour(now() - INTERVAL 2 HOUR)"))
    }

    @Test
    fun `runOnce skips finalization when ClickHouse is not initialized`() = runBlocking {
        every { ClickHouseClient.isInitialized() } returns false

        service().runOnce()

        assertTrue(captured.isEmpty(), "no finalize query should be issued when uninitialized")
    }

    @Test
    fun `start launches a finalization pass and stop cancels the loop`() = runBlocking {
        val firstRun = CompletableDeferred<String>()
        coEvery { ClickHouseClient.executeLongRunning(any(), any()) } coAnswers {
            captured.add(firstArg())
            if (!firstRun.isCompleted) firstRun.complete(firstArg())
        }

        val svc = service()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            svc.start(scope)
            val sql = withTimeout(5_000) { firstRun.await() }
            assertTrue(sql.contains("apm_traces_final"))
        } finally {
            svc.stop()
            scope.cancel()
        }
    }

    @Test
    fun `start does nothing when disabled by config`() = runBlocking {
        val svc = service(enabled = false)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            svc.start(scope)
            delay(200)
            assertTrue(captured.isEmpty(), "disabled finalizer must not run")
        } finally {
            svc.stop()
            scope.cancel()
        }
    }

    @Test
    fun `start ignores a duplicate call while already running`() = runBlocking {
        val svc = service()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            svc.start(scope)
            svc.start(scope) // hits the "already running" guard
        } finally {
            svc.stop()
            scope.cancel()
        }
    }

    @Test
    fun `constructor rejects invalid configuration`() {
        assertFailsWith<IllegalArgumentException> { service(intervalSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { service(refreshWindowHours = 0) }
        assertFailsWith<IllegalArgumentException> { service(backfillWindowHours = 1, refreshWindowHours = 2) }
        assertFailsWith<IllegalArgumentException> { service(lookbackHours = -1) }
        assertFailsWith<IllegalArgumentException> { service(maxExecutionSeconds = 0) }
    }

    @Test
    fun `fromConfig applies defaults when keys are absent`() = runBlocking {
        val svc = TraceFinalizerBackgroundService.fromConfig(MapApplicationConfig())

        svc.runOnce() // first pass uses the default 50h backfill window
        assertEquals(1, captured.size)
        assertTrue(captured.single().contains("toStartOfHour(now() - INTERVAL 50 HOUR)"))
    }

    @Test
    fun `fromConfig reads provided values`() = runBlocking {
        val svc = TraceFinalizerBackgroundService.fromConfig(
            MapApplicationConfig(
                "traceFinalizer.enabled" to "true",
                "traceFinalizer.intervalSeconds" to "300",
                "traceFinalizer.refreshWindowHours" to "1",
                "traceFinalizer.backfillWindowHours" to "10",
                "traceFinalizer.lookbackHours" to "3",
                "traceFinalizer.maxExecutionSeconds" to "120",
            ),
        )

        svc.runOnce() // first pass uses the configured 10h backfill window
        assertEquals(1, captured.size)
        assertTrue(captured.single().contains("toStartOfHour(now() - INTERVAL 10 HOUR)"))
    }
}
