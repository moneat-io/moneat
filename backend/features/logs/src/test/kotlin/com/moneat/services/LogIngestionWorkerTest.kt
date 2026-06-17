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

import com.moneat.config.RedisConfig
import com.moneat.logs.models.QueuedLogBatch
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.models.LogEntryResponse
import com.moneat.logs.models.LogIndexResponse
import com.moneat.logs.models.LogPipelineResponse
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogIngestionWorker
import com.moneat.logs.services.LogManagementService
import com.moneat.logs.services.LogService
import com.moneat.monitoring.OperationalMetrics
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains

class LogIngestionWorkerTest {
    @BeforeTest
    fun resetMetricsBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun resetMetricsAfter() {
        OperationalMetrics.resetForTest()
    }

    @Test
    fun `processMessageForTest sends malformed payload to DLQ`() =
        runBlocking {
            val worker = LogIngestionWorker("log:q", "log:dlq", 1)
            val redis = mockDlqStream()
            val malformed = "not-json"

            try {
                worker.processMessageForTest(workerId = 1, payload = malformed)

                verify(exactly = 1) {
                    redis.xadd("log:dlq:stream", any<XAddArgs>(), any<Map<String, String>>())
                }
            } finally {
                unmockkObject(RedisConfig)
            }
        }

    @Test
    fun `processMessageForTest sends payload to DLQ when insert fails`() =
        runBlocking {
            val worker = LogIngestionWorker("log:q", "log:dlq", 1)
            val redis = mockDlqStream()
            val message =
                LogService(LogRepositoryImpl()).encodeQueueMessage(
                    QueuedLogBatch(
                        organizationId = 99L,
                        source = "sdk",
                        logs =
                        listOf(
                            QueuedLogEntry(
                                logId = "00000000-0000-0000-0000-000000000000",
                                timestampMs = 1_738_372_400_000,
                                level = "info",
                                message = "hello",
                                body = "hello",
                                service = "api",
                                environment = "prod",
                                host = "host-1",
                                source = "sdk",
                                containerName = "",
                                containerId = "",
                                containerImage = "",
                                traceId = "",
                                spanId = ""
                            )
                        )
                    )
                )

            try {
                worker.processMessageForTest(workerId = 2, payload = message)

                verify(exactly = 1) {
                    redis.xadd("log:dlq:stream", any<XAddArgs>(), any<Map<String, String>>())
                }
            } finally {
                unmockkObject(RedisConfig)
            }
        }

    @Test
    fun `processMessageForTest applies pipelines indexes quota and publishes live logs`() =
        runBlocking {
            val logService = mockk<LogService>()
            val logIndexService = mockk<LogIndexService>()
            val logManagementService = mockk<LogManagementService>()
            val originalEntry = queuedEntry(logId = "route-me", level = "error", message = "before")
            val pipedEntry = originalEntry.copy(message = "after")
            val inserted = listOf(logEntryResponse(logId = "route-me", message = "after"))
            val batch = QueuedLogBatch(organizationId = 99L, source = "sdk", logs = listOf(originalEntry))
            val payload = LogService(LogRepositoryImpl()).encodeQueueMessage(batch)
            val index = logIndexResponse(name = "errors", filterQuery = "level:error")
            val pipeline = logPipelineResponse(name = "Cleanup")

            every { logService.decodeQueueMessage(payload) } returns batch
            coEvery { logIndexService.getActiveIndexesCached(99) } returns listOf(index)
            coEvery { logManagementService.getActivePipelinesCached(99) } returns listOf(pipeline)
            every { logManagementService.applyPipelines(listOf(originalEntry), listOf(pipeline)) } returns
                listOf(pipedEntry)
            coEvery {
                logIndexService.filterWithinDailyQuota(99, any(), listOf(index))
            } answers {
                secondArg()
            }
            coEvery { logService.insertBatch(any()) } returns inserted
            coEvery { logService.publishLiveLogs(99L, inserted) } returns Unit

            val worker = LogIngestionWorker(
                queueKey = "log:q",
                dlqKey = "log:dlq",
                workerCount = 1,
                logService = logService,
                logIndexService = logIndexService,
                logManagementService = logManagementService
            )

            worker.processMessageForTest(workerId = 4, payload = payload)

            coVerify {
                logService.insertBatch(
                    match { insertedBatch ->
                        insertedBatch.logs.single().indexName == "errors" &&
                            insertedBatch.logs.single().message == "after"
                    }
                )
            }
            coVerify { logService.publishLiveLogs(99L, inserted) }
        }

    @Test
    fun `processMessageForTest skips org scoped lookups when org id is outside Int range`() =
        runBlocking {
            val logService = mockk<LogService>()
            val logIndexService = mockk<LogIndexService>(relaxed = true)
            val logManagementService = mockk<LogManagementService>(relaxed = true)
            val entry = queuedEntry(logId = "large-org", level = "info", message = "hello")
            val orgId = Int.MAX_VALUE.toLong() + 1L
            val batch = QueuedLogBatch(organizationId = orgId, source = "sdk", logs = listOf(entry))
            val payload = LogService(LogRepositoryImpl()).encodeQueueMessage(batch)
            val inserted = listOf(logEntryResponse(logId = "large-org", message = "hello"))

            every { logService.decodeQueueMessage(payload) } returns batch
            every { logManagementService.applyPipelines(listOf(entry), emptyList()) } returns listOf(entry)
            coEvery { logService.insertBatch(batch) } returns inserted
            coEvery { logService.publishLiveLogs(orgId, inserted) } returns Unit

            val worker = LogIngestionWorker(
                queueKey = "log:q",
                dlqKey = "log:dlq",
                workerCount = 1,
                logService = logService,
                logIndexService = logIndexService,
                logManagementService = logManagementService
            )

            worker.processMessageForTest(workerId = 5, payload = payload)

            coVerify(exactly = 0) { logIndexService.getActiveIndexesCached(any()) }
            coVerify(exactly = 0) { logManagementService.getActivePipelinesCached(any()) }
            coVerify(exactly = 0) { logIndexService.filterWithinDailyQuota(any(), any(), any()) }
            coVerify { logService.publishLiveLogs(orgId, inserted) }
        }

    @Test
    fun `processMessageForTest returns before insert when quota filtering drops all logs`() =
        runBlocking {
            val logService = mockk<LogService>()
            val logIndexService = mockk<LogIndexService>()
            val logManagementService = mockk<LogManagementService>()
            val entry = queuedEntry(logId = "quota", level = "info", message = "hello")
            val batch = QueuedLogBatch(organizationId = 100L, source = "sdk", logs = listOf(entry))
            val payload = LogService(LogRepositoryImpl()).encodeQueueMessage(batch)
            val index = logIndexResponse(name = "main", filterQuery = "")

            every { logService.decodeQueueMessage(payload) } returns batch
            coEvery { logIndexService.getActiveIndexesCached(100) } returns listOf(index)
            coEvery { logManagementService.getActivePipelinesCached(100) } returns emptyList()
            every { logManagementService.applyPipelines(listOf(entry), emptyList()) } returns listOf(entry)
            coEvery { logIndexService.filterWithinDailyQuota(100, any(), listOf(index)) } returns emptyList()

            val worker = LogIngestionWorker(
                queueKey = "log:q",
                dlqKey = "log:dlq",
                workerCount = 1,
                logService = logService,
                logIndexService = logIndexService,
                logManagementService = logManagementService
            )

            worker.processMessageForTest(workerId = 6, payload = payload)

            coVerify(exactly = 0) { logService.insertBatch(any()) }
            coVerify(exactly = 0) { logService.publishLiveLogs(any(), any()) }
        }

    @Test
    fun `processMessageForTest records DLQ failure when stream write fails`() =
        runBlocking {
            val worker = LogIngestionWorker("log:q", "log:dlq", 1)
            val malformed = "not-json"

            mockkObject(RedisConfig)
            try {
                every { RedisConfig.sync().xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) } throws
                    IllegalStateException("dlq down")

                worker.processMessageForTest(workerId = 3, payload = malformed)
            } finally {
                unmockkObject(RedisConfig)
            }

            val rendered = OperationalMetrics.scrape()
            assertContains(rendered, "moneat_worker_dlq_pushes_total")
            assertContains(rendered, "worker=\"Log\"")
            assertContains(rendered, "dlq_key=\"log:dlq:stream\"")
            assertContains(rendered, "status=\"failure\"")
        }

    private fun mockDlqStream(): RedisCommands<String, String> {
        val redis = mockk<RedisCommands<String, String>>()
        mockkObject(RedisConfig)
        every { RedisConfig.sync() } returns redis
        every { redis.xadd("log:dlq:stream", any<XAddArgs>(), any<Map<String, String>>()) } returns "1-0"
        return redis
    }

    private fun queuedEntry(
        logId: String,
        level: String,
        message: String
    ): QueuedLogEntry =
        QueuedLogEntry(
            logId = logId,
            timestampMs = 1_738_372_400_000,
            level = level,
            message = message,
            body = message,
            service = "api",
            environment = "prod",
            host = "host-1",
            source = "sdk",
            containerName = "",
            containerId = "",
            containerImage = "",
            traceId = "",
            spanId = ""
        )

    private fun logIndexResponse(
        name: String,
        filterQuery: String
    ): LogIndexResponse =
        LogIndexResponse(
            id = resourceId(1),
            name = name,
            filterQuery = filterQuery,
            retentionDays = 30,
            samplingRate = 1.0f,
            priority = 0,
            isActive = true,
            createdAt = "2026-06-04T00:00:00Z",
            updatedAt = "2026-06-04T00:00:00Z"
        )

    private fun logPipelineResponse(name: String): LogPipelineResponse =
        LogPipelineResponse(
            id = resourceId(1),
            name = name,
            description = "",
            steps = emptyList(),
            priority = 0,
            isActive = true,
            createdAt = "2026-06-04T00:00:00Z",
            updatedAt = "2026-06-04T00:00:00Z"
        )

    private fun resourceId(id: Int): String =
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"

    private fun logEntryResponse(
        logId: String,
        message: String
    ): LogEntryResponse =
        LogEntryResponse(
            logId = logId,
            timestamp = "2026-06-04T00:00:00Z",
            level = "info",
            message = message,
            body = message,
            service = "api",
            environment = "prod",
            host = "host-1",
            source = "sdk",
            containerName = "",
            containerId = "",
            containerImage = "",
            traceId = "",
            spanId = ""
        )
}
