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

import io.lettuce.core.Consumer
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs.StreamOffset
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mu.KLogger
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisStreamQueueOperationsTest {
    private val redis = mockk<RedisCommands<String, String>>()
    private val logger = mockk<KLogger>(relaxed = true)

    @Test
    fun `ensureConsumerGroup ignores an existing group`() {
        every {
            redis.xgroupCreate(any<StreamOffset<String>>(), "moneat:logs:workers", any<XGroupCreateArgs>())
        } throws RedisCommandExecutionException("BUSYGROUP Consumer Group name already exists")

        RedisStreamQueueOperations.ensureConsumerGroup(
            redis,
            streamKey = "moneat:logs:queue:stream",
            consumerGroup = "moneat:logs:workers",
        )

        verify(exactly = 1) {
            redis.xgroupCreate(any<StreamOffset<String>>(), "moneat:logs:workers", any<XGroupCreateArgs>())
        }
    }

    @Test
    fun `readMessages recreates a missing stream consumer group`() {
        val spec = logQueueSpec()
        every {
            redis.xautoclaim("moneat:logs:queue:stream", any<XAutoClaimArgs<String>>())
        } throws RedisCommandExecutionException(
            "NOGROUP No such key 'moneat:logs:queue:stream' or consumer group 'moneat:logs:workers'",
        )
        every {
            redis.xgroupCreate(any<StreamOffset<String>>(), "moneat:logs:workers", any<XGroupCreateArgs>())
        } returns "OK"

        val messages = RedisStreamQueueOperations.readMessages(
            redis,
            spec,
            Consumer.from("moneat:logs:workers", "logs-host-1"),
            logger,
        )

        assertEquals(emptyList(), messages)
        verify(exactly = 1) {
            redis.xgroupCreate(any<StreamOffset<String>>(), "moneat:logs:workers", any<XGroupCreateArgs>())
        }
    }

    @Test
    fun `acknowledge deletes acknowledged stream entries`() {
        every { redis.xack("moneat:logs:queue:stream", "moneat:logs:workers", "1-0", "2-0") } returns 2L
        every { redis.xdel("moneat:logs:queue:stream", "1-0", "2-0") } returns 2L

        RedisStreamQueueOperations.acknowledge(
            redis,
            streamKey = "moneat:logs:queue:stream",
            consumerGroup = "moneat:logs:workers",
            ids = arrayOf("1-0", "2-0"),
            logger = logger,
        )

        verify(exactly = 1) { redis.xack("moneat:logs:queue:stream", "moneat:logs:workers", "1-0", "2-0") }
        verify(exactly = 1) { redis.xdel("moneat:logs:queue:stream", "1-0", "2-0") }
    }

    @Test
    fun `acknowledge keeps stream processing successful when cleanup fails`() {
        every { redis.xack("moneat:logs:queue:stream", "moneat:logs:workers", "1-0") } returns 1L
        every {
            redis.xdel("moneat:logs:queue:stream", "1-0")
        } throws RedisCommandExecutionException("READONLY cleanup failed")

        RedisStreamQueueOperations.acknowledge(
            redis,
            streamKey = "moneat:logs:queue:stream",
            consumerGroup = "moneat:logs:workers",
            ids = arrayOf("1-0"),
            logger = logger,
        )

        verify(exactly = 1) { redis.xack("moneat:logs:queue:stream", "moneat:logs:workers", "1-0") }
        verify(exactly = 1) { redis.xdel("moneat:logs:queue:stream", "1-0") }
    }

    @Test
    fun `acknowledge ignores missing consumer group during ack`() {
        every {
            redis.xack("moneat:logs:queue:stream", "moneat:logs:workers", "1-0")
        } throws RedisCommandExecutionException(
            "NOGROUP No such key 'moneat:logs:queue:stream' or consumer group 'moneat:logs:workers'",
        )

        RedisStreamQueueOperations.acknowledge(
            redis,
            streamKey = "moneat:logs:queue:stream",
            consumerGroup = "moneat:logs:workers",
            ids = arrayOf("1-0"),
            logger = logger,
        )

        verify(exactly = 1) { redis.xack("moneat:logs:queue:stream", "moneat:logs:workers", "1-0") }
        verify(exactly = 0) { redis.xdel("moneat:logs:queue:stream", "1-0") }
    }

    private fun logQueueSpec(): IngestionQueueSpec =
        IngestionQueueSpec(
            pipeline = IngestionPipeline.LOGS,
            workerCount = 1,
            streamKey = "moneat:logs:queue:stream",
            dlqStreamKey = "moneat:logs:dlq:stream",
            consumerGroup = "moneat:logs:workers",
            batchSize = 50,
            claimIdleMs = 300_000,
            maxDeliveries = 5,
            readTimeoutMs = 5_000,
            maxPendingEntries = 250_000,
            dlqStreamMaxLen = 10_000,
        )
}
