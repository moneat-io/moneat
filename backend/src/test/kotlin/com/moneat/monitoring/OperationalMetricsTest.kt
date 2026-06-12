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

package com.moneat.monitoring

import com.moneat.config.EnvConfig
import com.moneat.config.RedisConfig
import io.lettuce.core.Range
import io.lettuce.core.StreamMessage
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.models.stream.PendingMessages
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class OperationalMetricsTest {
    @BeforeTest
    fun resetBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        unmockkObject(EnvConfig)
        unmockkObject(RedisConfig)
        OperationalMetrics.resetForTest()
    }

    @Test
    fun `renders worker counters in prometheus format`() {
        OperationalMetrics.recordWorkerProcessingFailure("DD metric", 1, IllegalStateException("boom"))
        OperationalMetrics.recordWorkerMessageProcessed("DD metric", 1)
        OperationalMetrics.recordDlqPush("DD metric", "moneat:metrics:dlq", "success")

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "# TYPE moneat_worker_processing_failures_total counter")
        assertContains(rendered, "moneat_worker_processing_failures_total")
        assertContains(rendered, "moneat_worker_dlq_pushes_total")
        assertContains(rendered, "moneat_worker_messages_processed_total")
        assertContains(rendered, "exception=\"IllegalStateException\"")
        assertContains(rendered, "worker=\"DD metric\"")
        assertContains(rendered, "worker_id=\"1\"")
        assertContains(rendered, "dlq_key=\"moneat:metrics:dlq\"")
        assertContains(rendered, "status=\"success\"")
        assertContains(rendered, "moneat_worker_last_success_timestamp_seconds")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders clickhouse request counters and histogram`() {
        OperationalMetrics.recordClickHouseRequest("execute", "success", 0.02)

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "moneat_clickhouse_requests_total")
        assertContains(rendered, "operation=\"execute\"")
        assertContains(rendered, "status=\"success\"")
        assertContains(rendered, "moneat_clickhouse_request_duration_seconds_bucket")
        assertContains(rendered, "le=\"0.025\"")
        assertContains(rendered, "moneat_clickhouse_request_duration_seconds_count")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders datasource failure counters`() {
        OperationalMetrics.recordDatasourceQueryFailure("prometheus", "query", "http_422")

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "moneat_datasource_query_failures_total")
        assertContains(rendered, "failure=\"http_422\"")
        assertContains(rendered, "operation=\"query\"")
        assertContains(rendered, "source_type=\"prometheus\"")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders dependency health metrics`() {
        OperationalMetrics.recordDependencyHealth("postgres", healthy = true, durationSeconds = 0.01)
        OperationalMetrics.recordDependencyHealth(
            "redis",
            healthy = false,
            durationSeconds = 0.02,
            cause = IllegalStateException("down")
        )

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "moneat_dependency_health")
        assertContains(rendered, "dependency=\"postgres\"")
        assertContains(rendered, "dependency=\"redis\"")
        assertContains(rendered, "moneat_dependency_health_checks_total")
        assertContains(rendered, "exception=\"IllegalStateException\"")
        assertContains(rendered, "status=\"failure\"")
        assertContains(rendered, "moneat_dependency_health_check_duration_seconds_bucket")
        assertContains(rendered, "moneat_dependency_last_success_timestamp_seconds")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders registered queue depth gauges`() {
        OperationalMetrics.registerWorkerQueues("Event", "moneat:events:queue", "moneat:events:dlq")

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "moneat_worker_queue_depth")
        assertContains(rendered, "queue_key=\"moneat:events:queue\"")
        assertContains(rendered, "queue_type=\"primary\"")
        assertContains(rendered, "queue_key=\"moneat:events:dlq\"")
        assertContains(rendered, "queue_type=\"dlq\"")
        assertContains(rendered, "worker=\"Event\"")
        assertContains(rendered, "moneat_worker_dlq_depth")
        assertContains(rendered, "dlq_key=\"moneat:events:dlq\"")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders ingestion queue mode defaults`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("INGESTION_QUEUE_BACKEND") } returns null
        every { EnvConfig.get("QUEUE_BACKEND") } returns null
        every { EnvConfig.get("INGESTION_QUEUE_READ_MODE") } returns null

        OperationalMetrics.bindSystemMetrics()

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "moneat_ingestion_queue_mode")
        assertContains(rendered, "backend=\"redis_list\"")
        assertContains(rendered, "read_mode=\"list\"")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders registered stream gauges`() {
        val streamKey = "moneat:logs:queue:stream"
        val consumerGroup = "moneat:logs:workers"
        val redis = mockk<RedisCommands<String, String>>()
        val oldStreamId = "${System.currentTimeMillis() - 10_000L}-0"

        mockkObject(RedisConfig)
        every { RedisConfig.isConnected() } returns true
        every { RedisConfig.sync() } returns redis
        every {
            redis.xpending(streamKey, consumerGroup)
        } returns PendingMessages(2, Range.create(oldStreamId, oldStreamId), mapOf("worker-1" to 2L))
        every {
            redis.xrange(streamKey, any<Range<String>>(), any())
        } returns listOf(StreamMessage(streamKey, oldStreamId, emptyMap()))

        OperationalMetrics.registerWorkerStream(
            workerName = "Log",
            streamKey = streamKey,
            streamType = "primary",
            consumerGroup = consumerGroup,
        )

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "moneat_worker_stream_pending_messages")
        assertContains(rendered, "moneat_worker_stream_oldest_message_age_seconds")
        assertContains(rendered, "stream_key=\"$streamKey\"")
        assertContains(rendered, "consumer_group=\"$consumerGroup\"")
        assertContains(rendered, "stream_type=\"primary\"")
        assertContains(rendered, " 2.0")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders datadog metric ingest health metrics`() {
        // ──── Arrange ────
        OperationalMetrics.registerQueue("DD metric", "moneat:metrics:queue", "primary")
        OperationalMetrics.registerQueue("DD metric", "moneat:metrics:queue:processing", "processing")
        OperationalMetrics.registerDlq("DD metric", "moneat:metrics:dlq")
        OperationalMetrics.recordDatadogMetricPayloadQueued(metricRows = 10)
        OperationalMetrics.recordDatadogMetricInsert(
            mode = "combined",
            status = "success",
            payloadCount = 2,
            rowCount = 10,
            durationSeconds = 0.02,
        )
        OperationalMetrics.recordDatadogMetricInsert(
            mode = "single",
            status = "failure",
            payloadCount = 1,
            rowCount = 4,
            durationSeconds = 0.03,
            cause = IllegalStateException("insert failed"),
        )
        OperationalMetrics.recordDatadogMetricInsertFallback(
            payloadCount = 2,
            rowCount = 10,
            cause = IllegalArgumentException("fallback"),
        )
        OperationalMetrics.recordDatadogMetricPayloadAck("success")
        OperationalMetrics.recordDatadogMetricPayloadAck("failure")
        OperationalMetrics.recordDatadogMetricProcessingRecovery(
            recoveredPayloads = 3,
            status = "success",
        )
        OperationalMetrics.recordDatadogMetricProcessingRecovery(
            recoveredPayloads = 0,
            status = "failure",
            cause = IllegalStateException("recovery failed"),
        )

        // ──── Act ────
        val rendered = OperationalMetrics.scrape()

        // ──── Assert ────
        assertContains(rendered, "moneat_datadog_metric_payloads_queued_total")
        assertContains(rendered, "moneat_datadog_metric_points_queued_total")
        assertContains(rendered, "moneat_datadog_metric_insert_chunks_total")
        assertContains(rendered, "moneat_datadog_metric_insert_duration_seconds_bucket")
        assertContains(rendered, "moneat_datadog_metric_insert_payloads_count")
        assertContains(rendered, "moneat_datadog_metric_insert_rows_count")
        assertContains(rendered, "moneat_datadog_metric_insert_fallbacks_total")
        assertContains(rendered, "moneat_datadog_metric_fallback_payloads_count")
        assertContains(rendered, "moneat_datadog_metric_fallback_rows_count")
        assertContains(rendered, "moneat_datadog_metric_payload_acks_total")
        assertContains(rendered, "moneat_datadog_metric_processing_recoveries_total")
        assertContains(rendered, "moneat_datadog_metric_processing_recovered_payloads_count")
        assertContains(rendered, "queue_type=\"processing\"")
        assertContains(rendered, "mode=\"combined\"")
        assertContains(rendered, "mode=\"single\"")
        assertContains(rendered, "status=\"failure\"")
        assertContains(rendered, "exception=\"IllegalStateException\"")
        assertContains(rendered, "exception=\"IllegalArgumentException\"")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `renders background job metrics`() {
        OperationalMetrics.recordBackgroundJobRun("billing-metered-flush", success = true, durationSeconds = 0.05)
        OperationalMetrics.recordBackgroundJobRun(
            "billing-quota-notifications",
            success = false,
            durationSeconds = 0.1,
            cause = IllegalStateException("boom")
        )

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "moneat_background_job_runs_total")
        assertContains(rendered, "exception=\"none\"")
        assertContains(rendered, "job=\"billing-metered-flush\"")
        assertContains(rendered, "status=\"success\"")
        assertContains(rendered, "exception=\"IllegalStateException\"")
        assertContains(rendered, "job=\"billing-quota-notifications\"")
        assertContains(rendered, "status=\"failure\"")
        assertContains(rendered, "moneat_background_job_duration_seconds_bucket")
        assertContains(rendered, "moneat_background_job_last_success_timestamp_seconds")
        assertHasApplicationTag(rendered)
    }

    @Test
    fun `records timed background job success and failure`() = runBlocking {
        OperationalMetrics.recordTimedBackgroundJobRun("timed-success") {
            // Intentionally empty.
        }

        assertFailsWith<IllegalStateException> {
            OperationalMetrics.recordTimedBackgroundJobRun("timed-failure") {
                error("boom")
            }
        }

        val rendered = OperationalMetrics.scrape()

        assertContains(rendered, "job=\"timed-success\"")
        assertContains(rendered, "job=\"timed-failure\"")
        assertContains(rendered, "exception=\"IllegalStateException\"")
        assertContains(rendered, "moneat_background_job_duration_seconds_count")
        assertContains(rendered, "moneat_background_job_last_success_timestamp_seconds")
        assertHasApplicationTag(rendered)
    }

    private fun assertHasApplicationTag(rendered: String) {
        assertContains(rendered, "application=\"moneat-backend\"")
    }
}
