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

import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.QueuedSketchBatch
import com.moneat.datadog.services.QueuedSketchEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DatadogSketchIngestionWorkerTest {
    @Test
    fun `batches inserts and updates host freshness after persistence`() = runBlocking {
        val batch = QueuedSketchBatch(
            organizationId = 42L,
            sketches = listOf(QueuedSketchEntry(name = "latency", timestampMs = 1L, host = "host-1")),
        )
        val worker = DatadogSketchIngestionWorker("sketch:q", "sketch:dlq", 1)

        mockkObject(DatadogMetricService, DatadogHostService)
        try {
            every { DatadogMetricService.decodeSketchBatch("payload") } returns batch
            coEvery { DatadogMetricService.insertSketchBatches(listOf(batch)) } returns Unit
            every { DatadogHostService.touchHostLastSeen(42, setOf("host-1")) } returns Unit

            worker.processPayloads(1, listOf("payload"))

            coVerify(exactly = 1) { DatadogMetricService.insertSketchBatches(listOf(batch)) }
            verify(exactly = 1) { DatadogHostService.touchHostLastSeen(42, setOf("host-1")) }
        } finally {
            unmockkObject(DatadogMetricService, DatadogHostService)
        }
    }

    @Test
    fun `propagates transient insert failure for stream retry`() = runBlocking {
        val batch = QueuedSketchBatch(42L, listOf(QueuedSketchEntry(name = "latency", timestampMs = 1L)))
        val worker = DatadogSketchIngestionWorker("sketch:q", "sketch:dlq", 1)

        mockkObject(DatadogMetricService)
        try {
            every { DatadogMetricService.decodeSketchBatch("payload") } returns batch
            coEvery { DatadogMetricService.insertSketchBatches(listOf(batch)) } throws
                IllegalStateException("ClickHouse unavailable")

            assertFailsWith<IllegalStateException> {
                worker.processPayloads(1, listOf("payload"))
            }
        } finally {
            unmockkObject(DatadogMetricService)
        }
    }
}
