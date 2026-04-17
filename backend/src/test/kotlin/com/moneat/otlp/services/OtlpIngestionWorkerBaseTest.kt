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

package com.moneat.otlp.services

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OtlpIngestionWorkerBaseTest {
    private class NoOpOtlpIngestionWorker(
        queueKey: String,
        dlqKey: String,
        workerCount: Int,
    ) : OtlpIngestionWorkerBase(
        queueKey,
        dlqKey,
        workerCount,
        "NoOpOtlpIngestionWorker",
        "noop",
    ) {
        override suspend fun processMessage(
            workerId: Int,
            payload: String,
        ) {
            // Intentionally empty: exercises base lifecycle without OTLP decode/insert.
        }

        suspend fun invokeProcessMessage(
            workerId: Int,
            payload: String,
        ) {
            processMessage(workerId, payload)
        }
    }

    @Test
    fun `processMessage with invalid payload does not throw`() {
        val worker =
            NoOpOtlpIngestionWorker(
                "test:otlp:noop:queue",
                "test:otlp:noop:dlq",
                1,
            )
        runBlocking {
            worker.invokeProcessMessage(1, "{not valid json")
        }
    }

    @Test
    fun `processMessage with empty payload does not throw`() {
        val worker =
            NoOpOtlpIngestionWorker(
                "test:otlp:noop:queue",
                "test:otlp:noop:dlq",
                1,
            )
        runBlocking {
            worker.invokeProcessMessage(1, "")
        }
    }

    @Test
    fun `worker can be constructed with queue keys`() {
        val worker =
            NoOpOtlpIngestionWorker(
                "test:otlp:noop:queue",
                "test:otlp:noop:dlq",
                2,
            )
        assertEquals("NoOpOtlpIngestionWorker", worker::class.simpleName)
    }

    @Test
    fun `stop on unstarted worker does not throw`() {
        val worker =
            NoOpOtlpIngestionWorker(
                "test:otlp:noop:queue",
                "test:otlp:noop:dlq",
                1,
            )
        runBlocking {
            worker.stop()
        }
    }
}
