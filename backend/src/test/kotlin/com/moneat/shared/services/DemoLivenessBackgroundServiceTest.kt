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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoLivenessBackgroundServiceTest {

    @Test
    fun `heartbeatSql restamps demo hosts and excludes the down host`() {
        val sql = DemoLivenessBackgroundService.heartbeatSql("prod-worker-01")
        assertTrue(sql.contains("UPDATE hosts"))
        assertTrue(sql.contains("last_seen_at = NOW()"))
        assertTrue(sql.contains("status = 'up'"))
        assertTrue(sql.contains("organization_id = -1"))
        assertTrue(sql.contains("hostname <> 'prod-worker-01'"))
    }

    @Test
    fun `start does nothing when demo is disabled`() = runBlocking {
        val calls = AtomicInteger(0)
        val service = DemoLivenessBackgroundService(
            intervalSeconds = 0,
            demoEnabled = { false },
            refresh = { calls.incrementAndGet() },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        service.start(scope)
        delay(100)
        service.stop()
        scope.cancel()
        assertEquals(0, calls.get())
    }

    @Test
    fun `start refreshes demo hosts when enabled`() = runBlocking {
        val calls = AtomicInteger(0)
        val firstCall = CompletableDeferred<Unit>()
        val service = DemoLivenessBackgroundService(
            intervalSeconds = 0,
            demoEnabled = { true },
            refresh = {
                calls.incrementAndGet()
                if (!firstCall.isCompleted) firstCall.complete(Unit)
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            service.start(scope)
            withTimeout(2000) { firstCall.await() }
            assertTrue(calls.get() >= 1)
        } finally {
            service.stop()
            scope.cancel()
        }
    }

    @Test
    fun `refreshOnce passes the down host and swallows failures`() = runBlocking {
        var receivedHost: String? = null
        val ok = DemoLivenessBackgroundService(
            downHostname = "worker-x",
            refresh = { host -> receivedHost = host },
        )
        ok.refreshOnce()
        assertEquals("worker-x", receivedHost)

        // A failing refresh must not propagate out of refreshOnce.
        val failing = DemoLivenessBackgroundService(
            refresh = { error("boom") },
        )
        var threw = false
        try {
            failing.refreshOnce()
        } catch (_: Throwable) {
            threw = true
        }
        assertFalse(threw)
    }
}
