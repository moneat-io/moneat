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
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IngestionQueueSettingsTest {
    @AfterTest
    fun resetEnvConfigMock() {
        unmockkObject(EnvConfig)
    }

    @Test
    fun `queue backend defaults to redis lists`() {
        assertEquals(IngestionQueueBackend.REDIS_LIST, IngestionQueueBackend.from(null))
        assertEquals(IngestionQueueBackend.REDIS_LIST, IngestionQueueBackend.from(""))
        assertEquals(IngestionQueueBackend.REDIS_LIST, IngestionQueueBackend.from("redis-list"))
    }

    @Test
    fun `queue backend accepts redis stream aliases`() {
        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueBackend.from("redis-streams"))
        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueBackend.from("redis_streams"))
        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueBackend.from(" stream "))
    }

    @Test
    fun `read mode follows backend when unset`() {
        assertEquals(
            IngestionQueueReadMode.LIST,
            IngestionQueueReadMode.from(null, IngestionQueueBackend.REDIS_LIST)
        )
        assertEquals(
            IngestionQueueReadMode.STREAMS,
            IngestionQueueReadMode.from(null, IngestionQueueBackend.REDIS_STREAMS)
        )
    }

    @Test
    fun `read mode accepts explicit transition values`() {
        assertEquals(
            IngestionQueueReadMode.DUAL,
            IngestionQueueReadMode.from("dual", IngestionQueueBackend.REDIS_LIST)
        )
        assertEquals(
            IngestionQueueReadMode.STREAMS,
            IngestionQueueReadMode.from("redis-streams", IngestionQueueBackend.REDIS_LIST)
        )
        assertEquals(
            IngestionQueueReadMode.LIST,
            IngestionQueueReadMode.from("redis-list", IngestionQueueBackend.REDIS_STREAMS)
        )
    }

    @Test
    fun `pipeline parser accepts ids and enum names`() {
        assertEquals(IngestionPipeline.LOGS, IngestionPipeline.parse("logs"))
        assertEquals(IngestionPipeline.OTLP_TRACES, IngestionPipeline.parse("otlp-traces"))
        assertEquals(IngestionPipeline.DD_SECURITY, IngestionPipeline.parse("DD_SECURITY"))
        assertNull(IngestionPipeline.parse("unknown"))
    }

    @Test
    fun `backend reads primary backend env before legacy fallback`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("INGESTION_QUEUE_BACKEND") } returns "redis-streams"
        every { EnvConfig.get("QUEUE_BACKEND") } returns "redis-list"

        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueSettings.backend())
    }

    @Test
    fun `backend reads legacy queue backend when primary env is absent`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("INGESTION_QUEUE_BACKEND") } returns null
        every { EnvConfig.get("QUEUE_BACKEND") } returns "streams"

        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueSettings.backend())
    }

    @Test
    fun `read mode follows configured value and backend default`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("INGESTION_QUEUE_BACKEND") } returns "redis-streams"
        every { EnvConfig.get("INGESTION_QUEUE_READ_MODE") } returns null
        every { EnvConfig.get("QUEUE_BACKEND") } returns null

        assertEquals(IngestionQueueReadMode.STREAMS, IngestionQueueSettings.readMode())

        every { EnvConfig.get("INGESTION_QUEUE_READ_MODE") } returns "dual"

        assertEquals(IngestionQueueReadMode.DUAL, IngestionQueueSettings.readMode())
    }

    @Test
    fun `spec applies env overrides and clamps worker count`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get(any()) } returns null
        every { EnvConfig.get("INGESTION_LOGS_STREAM_KEY") } returns "custom:logs:stream"
        every { EnvConfig.get("INGESTION_LOGS_DLQ_STREAM_KEY") } returns "custom:logs:dlq:stream"
        every { EnvConfig.get("INGESTION_LOGS_CONSUMER_GROUP") } returns "custom-group"
        every { EnvConfig.get("INGESTION_LOGS_BATCH_SIZE") } returns "25"
        every { EnvConfig.get("INGESTION_LOGS_CLAIM_IDLE_MS") } returns "1234"
        every { EnvConfig.get("INGESTION_LOGS_MAX_DELIVERIES") } returns "9"
        every { EnvConfig.get("INGESTION_LOGS_READ_TIMEOUT_MS") } returns "321"

        val spec = IngestionQueueSettings.spec(IngestionPipeline.LOGS, "logs:q", "logs:dlq", workerCount = 0)

        assertEquals(1, spec.workerCount)
        assertEquals("custom:logs:stream", spec.streamKey)
        assertEquals("custom:logs:dlq:stream", spec.dlqStreamKey)
        assertEquals("custom-group", spec.consumerGroup)
        assertEquals(25, spec.batchSize)
        assertEquals(1_234L, spec.claimIdleMs)
        assertEquals(9, spec.maxDeliveries)
        assertEquals(321L, spec.readTimeoutMs)
    }

    @Test
    fun `spec falls back for invalid numeric env values`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get(any()) } returns null
        every { EnvConfig.get("INGESTION_LOGS_BATCH_SIZE") } returns "0"
        every { EnvConfig.get("INGESTION_LOGS_CLAIM_IDLE_MS") } returns "-1"
        every { EnvConfig.get("INGESTION_LOGS_MAX_DELIVERIES") } returns "not-a-number"
        every { EnvConfig.get("INGESTION_LOGS_READ_TIMEOUT_MS") } returns "0"

        val spec = IngestionQueueSettings.spec(IngestionPipeline.LOGS, "logs:q", "logs:dlq", workerCount = 2)

        assertEquals("logs:q:stream", spec.streamKey)
        assertEquals("logs:dlq:stream", spec.dlqStreamKey)
        assertEquals(IngestionPipeline.LOGS.consumerGroup, spec.consumerGroup)
        assertEquals(50, spec.batchSize)
        assertEquals(300_000L, spec.claimIdleMs)
        assertEquals(5, spec.maxDeliveries)
        assertEquals(5_000L, spec.readTimeoutMs)
    }

    @Test
    fun `selected pipelines handles all none and explicit values`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("INGESTION_PIPELINES") } returns null
        assertNull(IngestionQueueSettings.selectedPipelines())
        assertEquals(true, IngestionQueueSettings.isSelected(IngestionPipeline.LOGS))

        every { EnvConfig.get("INGESTION_PIPELINES") } returns " all "
        assertNull(IngestionQueueSettings.selectedPipelines())

        every { EnvConfig.get("INGESTION_PIPELINES") } returns "none"
        assertEquals(emptySet(), IngestionQueueSettings.selectedPipelines())
        assertEquals(false, IngestionQueueSettings.isSelected(IngestionPipeline.LOGS))

        every { EnvConfig.get("INGESTION_PIPELINES") } returns "logs,dd-security"
        assertEquals(
            setOf(IngestionPipeline.LOGS, IngestionPipeline.DD_SECURITY),
            IngestionQueueSettings.selectedPipelines(),
        )
        assertEquals(true, IngestionQueueSettings.isSelected(IngestionPipeline.LOGS))
        assertEquals(false, IngestionQueueSettings.isSelected(IngestionPipeline.LLM))
    }

    @Test
    fun `selected pipelines rejects unknown values`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("INGESTION_PIPELINES") } returns "logs,unknown"

        assertFailsWith<IllegalArgumentException> {
            IngestionQueueSettings.selectedPipelines()
        }
    }
}
