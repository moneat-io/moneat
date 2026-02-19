package com.moneat.services

import com.moneat.models.QueuedLogBatch
import com.moneat.models.QueuedLogEntry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LogIngestionWorkerTest {
    @Test
    fun `processMessageForTest sends malformed payload to DLQ`() = runBlocking {
        val worker = LogIngestionWorker("log:q", "log:dlq", 1)
        val dlq = mutableListOf<String>()
        val malformed = "not-json"

        worker.processMessageForTest(workerId = 1, payload = malformed) { dlq.add(it) }

        assertEquals(listOf(malformed), dlq)
    }

    @Test
    fun `processMessageForTest sends payload to DLQ when insert fails`() = runBlocking {
        val worker = LogIngestionWorker("log:q", "log:dlq", 1)
        val dlq = mutableListOf<String>()
        val message = LogService().encodeQueueMessage(
            QueuedLogBatch(
                projectId = 99,
                source = "sdk",
                logs = listOf(
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

        worker.processMessageForTest(workerId = 2, payload = message) { dlq.add(it) }

        assertEquals(listOf(message), dlq)
    }
}
