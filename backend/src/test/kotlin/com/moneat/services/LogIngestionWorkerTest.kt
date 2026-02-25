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

import com.moneat.logs.models.QueuedLogBatch
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.services.LogIngestionWorker
import com.moneat.logs.services.LogService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LogIngestionWorkerTest {
    @Test
    fun `processMessageForTest sends malformed payload to DLQ`() =
        runBlocking {
            val worker = LogIngestionWorker("log:q", "log:dlq", 1)
            val dlq = mutableListOf<String>()
            val malformed = "not-json"

            worker.processMessageForTest(workerId = 1, payload = malformed) { dlq.add(it) }

            assertEquals(listOf(malformed), dlq)
        }

    @Test
    fun `processMessageForTest sends payload to DLQ when insert fails`() =
        runBlocking {
            val worker = LogIngestionWorker("log:q", "log:dlq", 1)
            val dlq = mutableListOf<String>()
            val message =
                LogService().encodeQueueMessage(
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

            worker.processMessageForTest(workerId = 2, payload = message) { dlq.add(it) }

            assertEquals(listOf(message), dlq)
        }
}
