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

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains

class OperationalMetricsTest {
    @BeforeTest
    fun resetBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
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

    private fun assertHasApplicationTag(rendered: String) {
        assertContains(rendered, "application=\"moneat-backend\"")
    }
}
