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

import com.moneat.alerts.models.AlertSource
import com.moneat.incident.services.IncidentService
import com.moneat.shared.services.TaskLock
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.services.UptimeCheckExecutor
import com.moneat.uptime.services.UptimeScheduler
import com.moneat.uptime.services.UptimeService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.time.Instant

class UptimeSchedulerTest {

    private val uptimeService = mockk<UptimeService>(relaxed = true)
    private val checkExecutor = mockk<UptimeCheckExecutor>(relaxed = true)
    private val incidentService = mockk<IncidentService>(relaxed = true)

    private val scheduler = UptimeScheduler(
        uptimeService = uptimeService,
        checkExecutor = checkExecutor,
        incidentService = incidentService,
    )

    @AfterTest
    fun tearDown() {
        scheduler.stop()
        try {
            unmockkObject(TaskLock)
        } catch (_: Exception) {
            // TaskLock may not have been mocked in every test
        }
    }

    private fun testMonitor(
        id: UUID = UUID.randomUUID(),
        organizationId: Int = 1,
        type: String = "http",
        status: String = "up",
        retries: Int = 0,
        retryIntervalSeconds: Int = 1
    ): UptimeMonitorData = UptimeMonitorData(
        id = id,
        organizationId = organizationId,
        name = "Test Monitor",
        type = type,
        active = true,
        url = "https://example.com",
        intervalSeconds = 60,
        timeoutSeconds = 10,
        retries = retries,
        retryIntervalSeconds = retryIntervalSeconds,
        status = status,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    private fun mockTaskLockPassThrough() {
        mockkObject(TaskLock)
        coEvery {
            TaskLock.tryWithLock<Unit>(any(), any(), any(), any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[3] as suspend () -> Unit
            block()
        }
    }

    private suspend fun callPrivateSuspend(name: String, vararg args: Any?): Any? {
        val fn =
            UptimeScheduler::class.declaredFunctions.single { f ->
                f.name == name && f.parameters.drop(1).size == args.size
            }
        fn.isAccessible = true
        return fn.callSuspend(scheduler, *args)
    }

    @Test
    fun `start and stop lifecycle completes without error`() {
        scheduler.start()
        scheduler.stop()
    }

    @Test
    fun `start is idempotent - second call does not throw`() {
        scheduler.start()
        scheduler.start()
        scheduler.stop()
    }

    @Test
    fun `empty monitor list does not dispatch any checks`() =
        runBlocking {
            mockTaskLockPassThrough()
            every {
                uptimeService.getMonitorsDueForCheck()
            } returns emptyList()

            scheduler.start()
            delay(1500)
            scheduler.stop()

            coVerify(exactly = 0) {
                checkExecutor.executeCheck(any())
            }
        }

    @Test
    fun `demo org monitors with organizationId -1 are skipped`() =
        runBlocking {
            mockTaskLockPassThrough()
            val monitor = testMonitor(organizationId = -1)
            every {
                uptimeService.getMonitorsDueForCheck()
            } returns listOf(monitor)

            scheduler.start()
            delay(1500)
            scheduler.stop()

            coVerify(exactly = 0) {
                checkExecutor.executeCheck(any())
            }
        }

    @Test
    fun `push type monitors are not checked`() =
        runBlocking {
            mockTaskLockPassThrough()
            val monitor = testMonitor(type = "push")
            every {
                uptimeService.getMonitorsDueForCheck()
            } returns listOf(monitor)

            scheduler.start()
            delay(2000)
            scheduler.stop()

            coVerify(exactly = 0) {
                checkExecutor.executeCheck(any())
            }
        }

    @Test
    fun `valid HTTP monitor triggers check execution`() =
        runBlocking {
            mockTaskLockPassThrough()
            val monitor = testMonitor(status = "up")
            every {
                uptimeService.getMonitorsDueForCheck()
            } returns listOf(monitor)
            coEvery {
                checkExecutor.executeCheck(any())
            } returns CheckResult(
                status = 1,
                responseTimeMs = 100,
                statusCode = 200
            )

            scheduler.start()
            delay(2000)
            scheduler.stop()

            coVerify(atLeast = 1) {
                checkExecutor.executeCheck(any())
            }
        }

    @Test
    fun `getMonitorsDueForCheck exception does not crash scheduler`() =
        runBlocking {
            mockTaskLockPassThrough()
            every {
                uptimeService.getMonitorsDueForCheck()
            } throws RuntimeException("DB error")

            scheduler.start()
            delay(1500)
            scheduler.stop()

            coVerify(exactly = 0) {
                checkExecutor.executeCheck(any())
            }
        }

    @Test
    fun `heartbeat is recorded after successful check`() =
        runBlocking {
            mockTaskLockPassThrough()
            val monitor = testMonitor(status = "up")
            every {
                uptimeService.getMonitorsDueForCheck()
            } returns listOf(monitor)
            coEvery {
                checkExecutor.executeCheck(any())
            } returns CheckResult(
                status = 1,
                responseTimeMs = 50,
                statusCode = 200
            )

            scheduler.start()
            delay(2000)
            scheduler.stop()

            coVerify(atLeast = 1) {
                uptimeService.recordHeartbeat(monitor.id, any())
            }
        }

    // ──── Recovery ────

    @Test
    fun `notifyStatusChange auto resolves incident when monitor recovers`() =
        runBlocking {
            val monitor = testMonitor(status = "down")

            callPrivateSuspend(
                "notifyStatusChange",
                monitor,
                "down",
                "up",
                CheckResult(status = 1, responseTimeMs = 50, statusCode = 200, message = "recovered")
            )

            coVerify(exactly = 1) {
                incidentService.autoResolveAlert(
                    organizationId = monitor.organizationId,
                    source = AlertSource.UPTIME_MONITOR,
                    deduplicationKey = "moneat-uptime-${monitor.id}",
                    title = "Uptime Monitor Recovered: Test Monitor",
                    description = "Monitor 'Test Monitor' (http) is back up.",
                    moneatUrl = "http://localhost:5173/uptime/${monitor.id}",
                )
            }
        }
}
