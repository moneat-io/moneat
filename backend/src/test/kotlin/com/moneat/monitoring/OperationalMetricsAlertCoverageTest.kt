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

package com.moneat.monitoring

import com.moneat.config.RedisConfig
import io.lettuce.core.Range
import io.lettuce.core.StreamMessage
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.models.stream.PendingMessages
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OperationalMetricsAlertCoverageTest {
    @BeforeTest
    fun resetBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        unmockkObject(RedisConfig)
        OperationalMetrics.resetForTest()
    }

    @Test
    fun `reports age of first unconsumed message after consumer group cursor`() {
        val streamKey = "moneat:logs:queue:stream"
        val consumerGroup = "moneat:logs:workers"
        val redis = mockk<RedisCommands<String, String>>()
        val lastDeliveredId = "${System.currentTimeMillis() - 20_000L}-0"
        val unconsumedId = "${System.currentTimeMillis() - 10_000L}-0"

        mockkObject(RedisConfig)
        every { RedisConfig.isConnected() } returns true
        every { RedisConfig.sync() } returns redis
        every { redis.xpending(streamKey, consumerGroup) } returns
            PendingMessages(0, Range.create("0-0", "0-0"), emptyMap())
        every { redis.xinfoGroups(streamKey) } returns listOf(
            listOf(
                "name",
                consumerGroup,
                "last-delivered-id",
                lastDeliveredId,
                "lag",
                1L,
            )
        )
        every { redis.xrange(streamKey, any<Range<String>>(), any()) } returns listOf(
            StreamMessage(streamKey, lastDeliveredId, emptyMap()),
            StreamMessage(streamKey, unconsumedId, emptyMap()),
        )

        OperationalMetrics.registerWorkerStream("Log", streamKey, "primary", consumerGroup)

        val age = metricLine(
            OperationalMetrics.scrape(),
            "moneat_worker_stream_oldest_message_age_seconds",
            "stream_key=\"$streamKey\"",
            "stream_type=\"primary\"",
        ).substringAfterLast(' ').toDouble()

        assertTrue(age > 0.0)
    }

    @Test
    fun `reports zero age when consumer group lag has no returned entry`() {
        val streamKey = "moneat:logs:queue:stream"
        val consumerGroup = "moneat:logs:workers"
        val redis = mockk<RedisCommands<String, String>>()
        val lastDeliveredId = "${System.currentTimeMillis() - 20_000L}-0"

        mockkObject(RedisConfig)
        every { RedisConfig.isConnected() } returns true
        every { RedisConfig.sync() } returns redis
        every { redis.xpending(streamKey, consumerGroup) } returns
            PendingMessages(0, Range.create("0-0", "0-0"), emptyMap())
        every { redis.xinfoGroups(streamKey) } returns listOf(
            listOf(
                "name",
                consumerGroup,
                "last-delivered-id",
                lastDeliveredId,
                "lag",
                1L,
            )
        )
        every { redis.xrange(streamKey, any<Range<String>>(), any()) } returns listOf(
            StreamMessage(streamKey, lastDeliveredId, emptyMap()),
        )

        OperationalMetrics.registerWorkerStream("Log", streamKey, "primary", consumerGroup)

        val ageLine = metricLine(
            OperationalMetrics.scrape(),
            "moneat_worker_stream_oldest_message_age_seconds",
            "stream_key=\"$streamKey\"",
            "stream_type=\"primary\"",
        )

        assertTrue(ageLine.endsWith(" 0.0"), ageLine)
    }

    @Test
    fun `falls back to oldest retained entry when group lag is unavailable`() {
        val streamKey = "moneat:traces:queue:stream"
        val consumerGroup = "moneat:traces:workers"
        val redis = mockk<RedisCommands<String, String>>()
        val oldestId = "${System.currentTimeMillis() - 10_000L}-0"

        mockkObject(RedisConfig)
        every { RedisConfig.isConnected() } returns true
        every { RedisConfig.sync() } returns redis
        every { redis.xpending(streamKey, consumerGroup) } returns
            PendingMessages(0, Range.create("0-0", "0-0"), emptyMap())
        every { redis.xinfoGroups(streamKey) } returns listOf(
            mapOf(
                "name" to consumerGroup,
                "last-delivered-id" to oldestId,
            )
        )
        every { redis.xrange(streamKey, any<Range<String>>(), any()) } returns listOf(
            StreamMessage(streamKey, oldestId, emptyMap()),
        )

        OperationalMetrics.registerWorkerStream("Trace", streamKey, "primary", consumerGroup)

        val age = metricLine(
            OperationalMetrics.scrape(),
            "moneat_worker_stream_oldest_message_age_seconds",
            "stream_key=\"$streamKey\"",
            "stream_type=\"primary\"",
        ).substringAfterLast(' ').toDouble()

        assertTrue(age > 0.0)
    }

    @Test
    fun `parses non numeric redis group values through their string form`() {
        val streamKey = "moneat:metrics:queue:stream"
        val dlqKey = "moneat:metrics:dlq:stream"
        val consumerGroup = "moneat:metrics:workers"
        val redis = mockk<RedisCommands<String, String>>()
        val redisNumber = object {
            override fun toString(): String = "6"
        }

        mockkObject(RedisConfig)
        every { RedisConfig.isConnected() } returns true
        every { RedisConfig.sync() } returns redis
        every { redis.xpending(streamKey, consumerGroup) } returns
            PendingMessages(4, Range.create("1-0", "4-0"), mapOf("worker-1" to 4L))
        every { redis.xinfoGroups(streamKey) } returns listOf(
            mapOf("name" to consumerGroup, "lag" to redisNumber)
        )
        every { redis.llen(dlqKey) } returns 0L

        OperationalMetrics.registerWorkerQueues("Metric", streamKey, dlqKey, consumerGroup)

        val queueLine = metricLine(
            OperationalMetrics.scrape(),
            "moneat_worker_queue_depth",
            "queue_key=\"$streamKey\"",
            "queue_type=\"primary\"",
            "worker=\"Metric\"",
        )

        assertTrue(queueLine.endsWith(" 10.0"), queueLine)
    }

    private fun metricLine(rendered: String, metricName: String, vararg labels: String): String =
        rendered.lineSequence()
            .firstOrNull { line ->
                line.startsWith(metricName) && labels.all { label -> line.contains(label) }
            }
            ?: error("Missing metric $metricName with labels ${labels.joinToString()}")
}
