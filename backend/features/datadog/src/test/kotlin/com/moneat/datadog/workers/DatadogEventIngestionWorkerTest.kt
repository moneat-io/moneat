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

package com.moneat.datadog.workers

import com.moneat.config.RedisConfig
import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.QueuedEventBatch
import com.moneat.datadog.services.QueuedEventEntry
import io.lettuce.core.XAddArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DatadogEventIngestionWorkerTest {
    @Test
    fun `processPayloads combines events and refreshes hosts after insert`() = runBlocking {
        val batch = QueuedEventBatch(42L, listOf(QueuedEventEntry("event", host = "host-1", timestampMs = 1L)))
        val worker = DatadogEventIngestionWorker("test:dd:event:queue", "test:dd:event:dlq", 1)

        mockkObject(DatadogEventService, DatadogHostService)
        try {
            every { DatadogEventService.decodeEventBatch("payload") } returns batch
            coEvery { DatadogEventService.insertEventBatches(listOf(batch)) } returns Unit
            every { DatadogHostService.touchHostLastSeen(42, setOf("host-1")) } returns Unit

            worker.processPayloads(1, listOf("payload"))

            coVerify(exactly = 1) { DatadogEventService.insertEventBatches(listOf(batch)) }
            verify(exactly = 1) { DatadogHostService.touchHostLastSeen(42, setOf("host-1")) }
        } finally {
            unmockkObject(DatadogEventService, DatadogHostService)
        }
    }

    @Test
    fun `processMessage with invalid payload writes to dlq`() {
        val worker =
            DatadogEventIngestionWorker(
                "test:dd:event:queue",
                "test:dd:event:dlq",
                1,
            )

        mockkObject(RedisConfig)
        try {
            every {
                RedisConfig.sync().xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>())
            } returns "1-0"
            runBlocking {
                worker.processMessage(1, "{not valid json")
            }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `processMessage with empty payload writes to dlq`() {
        val worker =
            DatadogEventIngestionWorker(
                "test:dd:event:queue",
                "test:dd:event:dlq",
                1,
            )

        mockkObject(RedisConfig)
        try {
            every {
                RedisConfig.sync().xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>())
            } returns "1-0"
            runBlocking {
                worker.processMessage(1, "")
            }
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `worker can be constructed with queue keys`() {
        val worker =
            DatadogEventIngestionWorker(
                "test:dd:event:queue",
                "test:dd:event:dlq",
                2,
            )
        assertEquals("DatadogEventIngestionWorker", worker::class.simpleName)
    }

    @Test
    fun `stop on unstarted worker does not throw`() {
        val worker =
            DatadogEventIngestionWorker(
                "test:dd:event:queue",
                "test:dd:event:dlq",
                1,
            )
        worker.stop()
    }
}
