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

import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.QueuedServiceCheckBatch
import com.moneat.datadog.services.QueuedServiceCheckEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class DatadogServiceCheckIngestionWorkerTest {
    @Test
    fun `batches inserts and updates host freshness after persistence`() = runBlocking {
        val batch = QueuedServiceCheckBatch(
            organizationId = 42L,
            serviceChecks = listOf(
                QueuedServiceCheckEntry(checkName = "ready", host = "host-1", timestampMs = 1L)
            ),
        )
        val worker = DatadogServiceCheckIngestionWorker("checks:q", "checks:dlq", 1)

        mockkObject(DatadogEventService, DatadogHostService)
        try {
            every { DatadogEventService.decodeServiceCheckBatch("payload") } returns batch
            coEvery { DatadogEventService.insertServiceCheckBatches(listOf(batch)) } returns Unit
            every { DatadogHostService.touchHostLastSeen(42, setOf("host-1")) } returns Unit

            worker.processPayloads(1, listOf("payload"))

            coVerify(exactly = 1) { DatadogEventService.insertServiceCheckBatches(listOf(batch)) }
            verify(exactly = 1) { DatadogHostService.touchHostLastSeen(42, setOf("host-1")) }
        } finally {
            unmockkObject(DatadogEventService, DatadogHostService)
        }
    }
}
