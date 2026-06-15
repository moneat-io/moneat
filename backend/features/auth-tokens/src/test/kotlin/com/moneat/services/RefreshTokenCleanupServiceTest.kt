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

package com.moneat.services

import com.moneat.auth.services.RefreshTokenCleanupService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RefreshTokenCleanupServiceTest {

    @Test
    fun `start runs cleanup periodically and stop cancels future runs`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service =
                RefreshTokenCleanupService(
                    refreshTokenCleaner =
                    {
                        calls.incrementAndGet()
                        0
                    },
                    cleanupInterval = 15.milliseconds
                )

            service.start(scope)
            delay(60)
            service.stop()

            val countAtStop = calls.get()
            delay(40)
            scope.cancel()

            assertTrue(countAtStop >= 2, "Expected at least two cleanup runs, got $countAtStop")
            assertEquals(calls.get(), countAtStop, "Cleanup should stop running after stop()")
        }

    @Test
    fun `cleanup loop survives cleaner exceptions`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service =
                RefreshTokenCleanupService(
                    refreshTokenCleaner =
                    {
                        val current = calls.incrementAndGet()
                        if (current == 1) throw IllegalStateException("boom")
                        0
                    },
                    cleanupInterval = 15.milliseconds
                )

            service.start(scope)
            delay(60)
            service.stop()
            scope.cancel()

            assertTrue(calls.get() >= 2, "Cleanup loop should continue after an exception")
        }
}
