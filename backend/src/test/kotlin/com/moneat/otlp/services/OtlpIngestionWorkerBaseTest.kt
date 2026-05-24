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

import com.moneat.monitoring.OperationalMetrics
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
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

    private class FailingOtlpIngestionWorker : OtlpIngestionWorkerBase(
        "test:otlp:failing:queue",
        "test:otlp:failing:dlq",
        1,
        "FailingOtlpIngestionWorker",
        "failing",
    ) {
        override suspend fun processMessage(
            workerId: Int,
            payload: String,
        ) {
            error("boom")
        }
    }

    @BeforeTest
    fun resetMetricsBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun resetMetricsAfter() {
        OperationalMetrics.resetForTest()
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

    @Test
    fun `base payload processing records success and failure metrics`() {
        val successWorker =
            NoOpOtlpIngestionWorker(
                "test:otlp:noop:queue",
                "test:otlp:noop:dlq",
                1,
            )
        val failingWorker = FailingOtlpIngestionWorker()

        runBlocking {
            processBrpopPayloadFunction.callSuspend(successWorker, 1, "{}")
            processBrpopPayloadFunction.callSuspend(failingWorker, 2, "{}")
        }

        val rendered = OperationalMetrics.scrape()
        assertContains(rendered, "moneat_worker_messages_processed_total")
        assertContains(rendered, "worker=\"OTLP noop\"")
        assertContains(rendered, "worker_id=\"1\"")
        assertContains(rendered, "moneat_worker_processing_failures_total")
        assertContains(rendered, "worker=\"OTLP failing\"")
        assertContains(rendered, "worker_id=\"2\"")
        assertContains(rendered, "exception=\"IllegalStateException\"")
    }

    @Test
    fun `base brpop loop failure records metrics`() {
        val worker =
            NoOpOtlpIngestionWorker(
                "test:otlp:noop:queue",
                "test:otlp:noop:dlq",
                1,
            )

        runBlocking {
            onOtlpBrpopLoopFailureFunction.callSuspend(
                worker,
                3,
                IllegalStateException("redis down"),
                null,
                { _: StatefulRedisConnection<String, String> -> },
            )
        }

        val rendered = OperationalMetrics.scrape()
        assertContains(rendered, "moneat_worker_brpop_failures_total")
        assertContains(rendered, "worker=\"OTLP noop\"")
        assertContains(rendered, "worker_id=\"3\"")
        assertContains(rendered, "exception=\"IllegalStateException\"")
    }

    private companion object {
        private val processBrpopPayloadFunction = privateBaseFunction("processBrpopPayload")
        private val onOtlpBrpopLoopFailureFunction = privateBaseFunction("onOtlpBrpopLoopFailure")

        private fun privateBaseFunction(name: String): KFunction<*> =
            OtlpIngestionWorkerBase::class.declaredFunctions
                .single { it.name == name }
                .also { it.isAccessible = true }
    }
}
