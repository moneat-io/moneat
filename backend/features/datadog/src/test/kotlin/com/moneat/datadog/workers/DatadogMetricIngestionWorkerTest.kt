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
import com.moneat.monitoring.OperationalMetrics
import io.lettuce.core.XAddArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatadogMetricIngestionWorkerTest {

    @BeforeTest
    fun resetMetricsBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun resetMetricsAfter() {
        OperationalMetrics.resetForTest()
    }

    // ──── Payload Handling ────

    @Test
    fun `processMessage with invalid payload writes to dlq`() {
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
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
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
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
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                2,
            )
        assertEquals("DatadogMetricIngestionWorker", worker::class.simpleName)
    }

    @Test
    fun `processPayloads combines valid payloads and pushes malformed payloads individually to dlq`() = runBlocking {
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
            every { RedisConfig.sync().xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) } returns "2-0"
            coEvery { DatadogMetricService.insertMetricBatches(any()) } returns Unit

            worker.processPayloads(1, listOf("payload-1", "bad-payload", "payload-2"))

            coVerify(exactly = 1) {
                DatadogMetricService.insertMetricBatches(listOf(firstBatch, secondBatch))
            }
            val rendered = OperationalMetrics.scrape()
            assertContains(rendered, "moneat_datadog_metric_insert_chunks_total")
            assertContains(rendered, "mode=\"combined\"")
            assertContains(rendered, "status=\"success\"")
            assertContains(rendered, "moneat_datadog_metric_insert_rows_count")
            assertContains(rendered, "moneat_datadog_metric_insert_payloads_count")
            assertContains(rendered, "moneat_worker_dlq_pushes_total")
        } finally {
            unmockkObject(DatadogMetricService)
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `processPayloads flushes chunks at configured max rows`() = runBlocking {
        val firstBatch = metricBatch(1L, "cpu")
        val secondBatch = metricBatch(2L, "mem")
        val worker =
            DatadogMetricIngestionWorker(
                "test:dd:metric:queue",
                "test:dd:metric:dlq",
                1,
                maxRows = 1,
            )

        mockkObject(DatadogMetricService)
        mockkObject(RedisConfig)
        try {
            every { DatadogMetricService.decodeMetricBatch("payload-1") } returns firstBatch
            every { DatadogMetricService.decodeMetricBatch("payload-2") } returns secondBatch
            coEvery { DatadogMetricService.insertMetricBatches(any()) } returns Unit

            worker.processPayloads(1, listOf("payload-1", "payload-2"))

            coVerify(exactly = 1) { DatadogMetricService.insertMetricBatches(listOf(firstBatch)) }
            coVerify(exactly = 1) { DatadogMetricService.insertMetricBatches(listOf(secondBatch)) }

            val rendered = OperationalMetrics.scrape()
            assertContains(rendered, "moneat_datadog_metric_insert_chunks_total")
            assertContains(rendered, "mode=\"combined\"")
            assertContains(rendered, "status=\"success\"")
            assertContains(rendered, "moneat_datadog_metric_insert_rows_count")
        } finally {
            unmockkObject(DatadogMetricService)
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `processPayloads propagates transient combined insert failure for stream retry`() = runBlocking {
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
            coEvery { DatadogMetricService.insertMetricBatches(listOf(firstBatch, secondBatch)) } throws
                IllegalStateException("combined insert failed")
            assertFailsWith<IllegalStateException> {
                worker.processPayloads(1, listOf("payload-1", "payload-2"))
            }

            coVerify(exactly = 1) {
                DatadogMetricService.insertMetricBatches(listOf(firstBatch, secondBatch))
            }
            coVerify(exactly = 0) { DatadogMetricService.insertMetricBatch(any()) }

            val rendered = OperationalMetrics.scrape()
            assertContains(rendered, "mode=\"combined\"")
            assertContains(rendered, "status=\"failure\"")
            assertContains(rendered, "exception=\"IllegalStateException\"")
        } finally {
            unmockkObject(DatadogMetricService)
            unmockkObject(RedisConfig)
        }
    }

    // ──── Lifecycle ────

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

    // ──── Helpers ────

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
