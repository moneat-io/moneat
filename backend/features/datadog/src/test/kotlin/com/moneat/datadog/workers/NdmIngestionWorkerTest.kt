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

import com.moneat.datadog.networkdevices.NdmIngestionWorker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class NdmIngestionWorkerTest {
    @Test
    fun `processMessage with invalid payload does not throw`() {
        val worker =
            NdmIngestionWorker(
                "test:dd:ndm:queue",
                "test:dd:ndm:dlq",
                1,
            )
        runBlocking {
            worker.processMessage(1, "{not valid json")
        }
    }

    @Test
    fun `processMessage with empty payload does not throw`() {
        val worker =
            NdmIngestionWorker(
                "test:dd:ndm:queue",
                "test:dd:ndm:dlq",
                1,
            )
        runBlocking {
            worker.processMessage(1, "")
        }
    }

    @Test
    fun `worker can be constructed with queue keys`() {
        val worker =
            NdmIngestionWorker(
                "test:dd:ndm:queue",
                "test:dd:ndm:dlq",
                2,
            )
        assertEquals("NdmIngestionWorker", worker::class.simpleName)
    }

    @Test
    fun `stop on unstarted worker does not throw`() {
        val worker =
            NdmIngestionWorker(
                "test:dd:ndm:queue",
                "test:dd:ndm:dlq",
                1,
            )
        worker.stop()
    }
}
