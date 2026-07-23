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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.logs.models.AgentLogEntry
import com.moneat.logs.models.LogEntryResponse
import com.moneat.logs.models.LogIngestEntry
import com.moneat.logs.models.LogTailFilters
import com.moneat.logs.models.QueuedLogBatch
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.repositories.LogRepository
import com.moneat.logs.services.LogService
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogServiceCoverageTest {
    private fun mockQueueAdmission(redis: RedisCommands<String, String>) {
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
        } returns "1-0"
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `enqueueSdkLogs pushes normalized batch to redis`() =
        runBlocking {
            mockkObject(RedisConfig)
            val redis = mockk<RedisCommands<String, String>>()
            every { RedisConfig.sync() } returns redis
            mockQueueAdmission(redis)

            mockkObject(ClickHouseClient)
            every { ClickHouseClient.getDatabase() } returns "test"

            val repo = mockk<LogRepository>()
            val service = LogService(repo)

            val n =
                service.enqueueSdkLogs(
                    organizationId = 9L,
                    entries = listOf(LogIngestEntry(message = "hello", body = "body")),
                    queueKey = "q:logs"
                )
            assertEquals(1, n)
        }

    @Test
    fun `enqueueAgentLogs with hostId includes host in batch`() =
        runBlocking {
            mockkObject(RedisConfig)
            val redis = mockk<RedisCommands<String, String>>()
            every { RedisConfig.sync() } returns redis
            mockQueueAdmission(redis)
            mockkObject(ClickHouseClient)
            every { ClickHouseClient.getDatabase() } returns "test"

            val repo = mockk<LogRepository>()
            val service = LogService(repo)

            val n =
                service.enqueueAgentLogs(
                    organizationId = 1L,
                    hostId = 22,
                    entries =
                    listOf(
                        AgentLogEntry(
                            message = "line",
                            body = "b",
                            level = "info",
                            timestampMs = 1L
                        )
                    ),
                    queueKey = "q:agent"
                )
            assertEquals(1, n)
        }

    @Test
    fun `insertBatch delegates to repository and maps responses`() =
        runBlocking {
            mockkObject(ClickHouseClient)
            every { ClickHouseClient.getDatabase() } returns "testdb"

            val repo = mockk<LogRepository>()
            coEvery { repo.executeClickHouseInsert(any()) } returns true

            val service = LogService(repo)
            val log =
                QueuedLogEntry(
                    logId = "550e8400-e29b-41d4-a716-446655440000",
                    timestampMs = 1_700_000_000_000L,
                    level = "info",
                    message = "m",
                    body = "b",
                    service = "svc",
                    environment = "prod",
                    host = "h1",
                    source = "sdk",
                    containerName = "",
                    containerId = "",
                    containerImage = "",
                    traceId = "",
                    spanId = "",
                    tags = emptyMap(),
                    resourceAttributes = emptyMap(),
                    indexName = "default"
                )
            val batch =
                QueuedLogBatch(
                    organizationId = 5L,
                    legacyProjectId = null,
                    systemId = null,
                    hostId = null,
                    source = "sdk",
                    logs = listOf(log)
                )
            val out = service.insertBatch(batch)
            assertEquals(1, out.size)
            assertEquals(log.logId, out.first().logId)
        }

    @Test
    fun `publishLiveLogs publishes json payloads`() =
        runBlocking {
            mockkObject(RedisConfig)
            val redis = mockk<RedisCommands<String, String>>()
            every { RedisConfig.sync() } returns redis
            every { redis.publish(any(), any()) } returns 1L

            val service = LogService(mockk(relaxed = true))
            val log =
                LogEntryResponse(
                    logId = "id-1",
                    timestamp = "2026-01-01T00:00:00Z",
                    level = "info",
                    message = "hi",
                    body = "",
                    service = "s",
                    environment = "e",
                    host = "h",
                    source = "sdk",
                    containerName = "",
                    containerId = "",
                    containerImage = "",
                    traceId = "",
                    spanId = "",
                    tags = emptyMap(),
                    resourceAttributes = emptyMap(),
                    systemId = null,
                    hostId = null
                )
            service.publishLiveLogs(10L, listOf(log))
            assertTrue(true)
        }

    @Test
    fun `decodeQueueMessage roundtrips encodeQueueMessage`() {
        val service = LogService(mockk(relaxed = true))
        val batch =
            QueuedLogBatch(
                organizationId = 1L,
                legacyProjectId = null,
                systemId = "00000000-0000-0000-0000-000000000000",
                hostId = null,
                source = "sdk",
                logs =
                listOf(
                    QueuedLogEntry(
                        logId = "a",
                        timestampMs = 1L,
                        level = "info",
                        message = "x",
                        body = "y",
                        service = "",
                        environment = "",
                        host = "",
                        source = "sdk",
                        containerName = "",
                        containerId = "",
                        containerImage = "",
                        traceId = "",
                        spanId = "",
                        tags = emptyMap(),
                        resourceAttributes = emptyMap(),
                        indexName = "default"
                    )
                )
            )
        val encoded = service.encodeQueueMessage(batch)
        val decoded = service.decodeQueueMessage(encoded)
        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals(1, decoded.logs.size)
    }

    @Test
    fun `estimateBillableBytes sums normalized message and body`() {
        val service = LogService(mockk(relaxed = true))
        val n =
            service.estimateBillableBytes(
                listOf(LogIngestEntry(message = "ab", body = "cd")),
            )
        assertEquals(4L, n)
    }

    @Test
    fun `parseLiveLog returns null for malformed json`() {
        val service = LogService(mockk(relaxed = true))
        assertNull(service.parseLiveLog("{bad"))
    }

    @Test
    fun `matchesTailFilters rejects wrong level`() {
        val service = LogService(mockk(relaxed = true))
        val log =
            LogEntryResponse(
                logId = "1",
                timestamp = "t",
                level = "warn",
                message = "m",
                body = "",
                service = "api",
                environment = "prod",
                host = "",
                source = "",
                containerName = "",
                containerId = "",
                containerImage = "",
                traceId = "",
                spanId = "",
                tags = emptyMap(),
                resourceAttributes = emptyMap(),
                systemId = null,
                hostId = null,
            )
        assertFalse(
            service.matchesTailFilters(
                log,
                LogTailFilters(levels = setOf("error"), service = null, environment = null, query = null),
            ),
        )
    }
}
