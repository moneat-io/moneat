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

package com.moneat.ingestion.queue

import com.moneat.config.EnvConfig
import com.moneat.config.RedisConfig
import com.moneat.monitoring.OperationalMetrics
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import mu.KLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestionQueueClientTest {
    private val redis = mockk<RedisCommands<String, String>>()
    private val logger = mockk<KLogger>(relaxed = true)

    @BeforeTest
    fun setup() {
        mockkObject(EnvConfig)
        mockkObject(RedisConfig)
        every { EnvConfig.get(any()) } returns null
        every { RedisConfig.sync() } returns redis
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun teardown() {
        unmockkObject(EnvConfig)
        unmockkObject(RedisConfig)
        OperationalMetrics.resetForTest()
    }

    @Test
    fun `enqueue writes structured body to redis stream`() {
        every {
            redis.eval<String>(
                any<String>(),
                ScriptOutputType.VALUE,
                arrayOf("logs:queue:stream"),
                "250000",
                "payload",
                "logs",
                any<String>(),
            )
        } returns "1-0"

        val streamId = IngestionQueueClient.enqueue(IngestionPipeline.LOGS, "logs:queue", "payload")

        assertEquals("1-0", streamId)
        assertTrue(OperationalMetrics.scrape().contains("outcome=\"accepted\""))
    }

    @Test
    fun `enqueue registers the sibling dlq stream key`() {
        mockkObject(OperationalMetrics)
        every {
            OperationalMetrics.registerIngestionQueue(any(), any(), any(), any(), any())
        } just runs
        every { OperationalMetrics.recordIngestionAdmission(any(), any()) } just runs
        every {
            redis.eval<String>(
                any<String>(),
                ScriptOutputType.VALUE,
                arrayOf("moneat:logs:queue:stream"),
                "250000",
                "payload",
                "logs",
                any<String>(),
            )
        } returns "1-0"

        try {
            IngestionQueueClient.enqueue(IngestionPipeline.LOGS, "moneat:logs:queue", "payload")

            verify(exactly = 1) {
                OperationalMetrics.registerIngestionQueue(
                    pipeline = IngestionPipeline.LOGS,
                    streamKey = "moneat:logs:queue:stream",
                    dlqStreamKey = "moneat:logs:dlq:stream",
                    consumerGroup = IngestionPipeline.LOGS.consumerGroup,
                    capacity = 250_000L,
                )
            }
        } finally {
            unmockkObject(OperationalMetrics)
        }
    }

    @Test
    fun `enqueue rejects a full stream without trimming it`() {
        every {
            redis.eval<String>(
                any<String>(),
                ScriptOutputType.VALUE,
                any<Array<String>>(),
                any<String>(),
                any<String>(),
                any<String>(),
                any<String>(),
            )
        } returns null

        val error = assertFailsWith<IngestionQueueCapacityException> {
            IngestionQueueClient.enqueue(IngestionPipeline.LOGS, "logs:queue", "payload")
        }

        assertEquals(250_000L, error.capacity)
        assertTrue(OperationalMetrics.scrape().contains("outcome=\"capacity_rejected\""))
    }

    @Test
    fun `enqueue reports redis failures as queue unavailability`() {
        every {
            redis.eval<String>(
                any<String>(),
                ScriptOutputType.VALUE,
                any<Array<String>>(),
                any<String>(),
                any<String>(),
                any<String>(),
                any<String>(),
            )
        } throws RedisException("redis down")

        assertFailsWith<IngestionQueueUnavailableException> {
            IngestionQueueClient.enqueue(IngestionPipeline.LOGS, "logs:queue", "payload")
        }

        assertTrue(OperationalMetrics.scrape().contains("outcome=\"unavailable\""))
    }

    @Test
    fun `pushToDlq writes stream metadata for redelivery failures`() {
        val spec = IngestionQueueSettings.spec(IngestionPipeline.LOGS, "logs:queue", "logs:dlq", workerCount = 1)
        val bodySlot = slot<Map<String, String>>()
        val argsSlot = slot<XAddArgs>()
        every { redis.xadd("logs:dlq:stream", capture(argsSlot), capture(bodySlot)) } returns "2-0"

        val pushed =
            IngestionQueueClient.pushToDlq(
                logger,
                IngestionDlqRequest(
                    spec,
                    "payload",
                    workerId = 7,
                    cause = RedisException("redis down"),
                    streamId = "1-0",
                ),
            )

        assertTrue(pushed)
        assertEquals(10_000L, xaddMaxLen(argsSlot.captured))
        assertTrue(xaddApproximateTrimming(argsSlot.captured))
        assertEquals("payload", bodySlot.captured["payload"])
        assertEquals("logs", bodySlot.captured["pipeline"])
        assertEquals("RedisException", bodySlot.captured["error_type"])
        assertEquals("redis down", bodySlot.captured["error_message"])
        assertEquals("1-0", bodySlot.captured["original_stream_id"])
    }

    @Test
    fun `pushToDlq returns false when redis write fails`() {
        every { redis.xadd("logs:dlq:stream", any<XAddArgs>(), any<Map<String, String>>()) } throws
            RedisException("redis down")
        val spec = IngestionQueueSettings.spec(IngestionPipeline.LOGS, "logs:queue", "logs:dlq", workerCount = 1)

        val pushed =
            IngestionQueueClient.pushToDlq(
                logger,
                IngestionDlqRequest(spec, "payload", workerId = 1, cause = IllegalArgumentException("bad")),
            )

        assertFalse(pushed)
    }

    @Test
    fun `payloadField returns stream payload when present`() {
        assertEquals("payload", IngestionQueueClient.payloadField(mapOf("payload" to "payload")))
        assertNull(IngestionQueueClient.payloadField(mapOf("pipeline" to "logs")))
    }

    private fun xaddMaxLen(args: XAddArgs): Long? =
        args.javaClass.getDeclaredField("maxlen")
            .also { field -> field.isAccessible = true }
            .get(args) as? Long

    private fun xaddApproximateTrimming(args: XAddArgs): Boolean =
        args.javaClass.getDeclaredField("approximateTrimming")
            .also { field -> field.isAccessible = true }
            .getBoolean(args)
}
