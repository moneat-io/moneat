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
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.QueuedMetricBatch
import com.moneat.datadog.services.QueuedMetricEntry
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals

class DatadogMetricIngestionWorkerTest {
    @Test
    fun `processMessage with invalid payload does not throw`() {
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                1,
            )
        runBlocking {
            worker.processMessage(1, "{not valid json")
        }
    }

    @Test
    fun `processMessage with empty payload does not throw`() {
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                1,
            )
        runBlocking {
            worker.processMessage(1, "")
        }
    }

    @Test
    fun `worker can be constructed with queue keys`() {
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                2,
            )
        assertEquals("DatadogMetricIngestionWorker", worker::class.simpleName)
    }

    @Test
    fun `collectPayloadsForProcessing drains additional payloads with bounded rpop count`() {
        val redis = mockk<RedisCommands<String, String>>()
        every { redis.rpop("test:dd:metric:queue", 99L) } returns listOf("payload-2", "payload-3")
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                1,
            )

        val payloads = worker.collectPayloadsForProcessing(redis, "payload-1")

        assertEquals(listOf("payload-1", "payload-2", "payload-3"), payloads)
    }

    @Test
    fun `processPayloads combines valid payloads and pushes malformed payloads individually to dlq`() = runBlocking {
        val redis = mockk<RedisCommands<String, String>>(relaxed = true)
        val firstBatch = metricBatch(1L, "cpu")
        val secondBatch = metricBatch(2L, "mem")
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                1,
            )

        mockkObject(DatadogMetricService)
        mockkObject(RedisConfig)
        try {
            every { DatadogMetricService.decodeMetricBatch("payload-1") } returns firstBatch
            every { DatadogMetricService.decodeMetricBatch("bad-payload") } throws
                SerializationException("bad payload")
            every { DatadogMetricService.decodeMetricBatch("payload-2") } returns secondBatch
            every { RedisConfig.sync() } returns redis
            every { redis.rpush("test:dd:metric:dlq", "bad-payload") } returns 1L
            coEvery { DatadogMetricService.insertMetricBatches(any()) } returns Unit

            worker.processPayloads(1, listOf("payload-1", "bad-payload", "payload-2"))

            coVerify(exactly = 1) {
                DatadogMetricService.insertMetricBatches(listOf(firstBatch, secondBatch))
            }
            verify(exactly = 1) {
                redis.rpush("test:dd:metric:dlq", "bad-payload")
            }
        } finally {
            unmockkObject(DatadogMetricService)
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `processPayloads retries combined insert then falls back per payload`() = runBlocking {
        val redis = mockk<RedisCommands<String, String>>(relaxed = true)
        val firstBatch = metricBatch(1L, "cpu")
        val secondBatch = metricBatch(2L, "mem")
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                1,
            )

        mockkObject(DatadogMetricService)
        mockkObject(RedisConfig)
        try {
            every { DatadogMetricService.decodeMetricBatch("payload-1") } returns firstBatch
            every { DatadogMetricService.decodeMetricBatch("payload-2") } returns secondBatch
            every { RedisConfig.sync() } returns redis
            coEvery { DatadogMetricService.insertMetricBatches(listOf(firstBatch, secondBatch)) } throws
                IllegalStateException("combined insert failed")
            coEvery { DatadogMetricService.insertMetricBatch(firstBatch) } returns Unit
            coEvery { DatadogMetricService.insertMetricBatch(secondBatch) } returns Unit

            worker.processPayloads(1, listOf("payload-1", "payload-2"))

            coVerify(exactly = 2) {
                DatadogMetricService.insertMetricBatches(listOf(firstBatch, secondBatch))
            }
            coVerify(exactly = 1) { DatadogMetricService.insertMetricBatch(firstBatch) }
            coVerify(exactly = 1) { DatadogMetricService.insertMetricBatch(secondBatch) }
            verify(exactly = 0) { redis.rpush("test:dd:metric:dlq", "payload-1") }
            verify(exactly = 0) { redis.rpush("test:dd:metric:dlq", "payload-2") }
        } finally {
            unmockkObject(DatadogMetricService)
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `stop on unstarted worker does not throw`() {
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                1,
            )
        worker.stop()
    }

    private fun metricBatch(
        organizationId: Long,
        metricName: String,
    ): QueuedMetricBatch =
        QueuedMetricBatch(
            organizationId = organizationId,
            metrics = listOf(
                QueuedMetricEntry(
                    name = metricName,
                    type = "gauge",
                    timestampMs = 1_700_000_000_000L,
                    value = 1.0,
                )
            )
        )
}
