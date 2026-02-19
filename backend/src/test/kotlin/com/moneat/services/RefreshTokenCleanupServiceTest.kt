package com.moneat.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RefreshTokenCleanupServiceTest {

    @Test
    fun `start runs cleanup periodically and stop cancels future runs`() = runBlocking {
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = RefreshTokenCleanupService(
            refreshTokenCleaner = RefreshTokenCleaner {
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
        assertTrue(calls.get() == countAtStop, "Cleanup should stop running after stop()")
    }

    @Test
    fun `cleanup loop survives cleaner exceptions`() = runBlocking {
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = RefreshTokenCleanupService(
            refreshTokenCleaner = RefreshTokenCleaner {
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
