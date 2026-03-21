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

import com.moneat.config.ClickHouseClient
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtlpMetricsServiceTest {

    companion object {
        private const val TEST_SVC = "test-svc"
        private const val TEST_ENV = "prod"
        private const val TEST_HOST = "host-01"
        private const val TEST_TIME_UNIX_NANO_PRIMARY = 1700000000000000000L
        private const val TEST_TIMESTAMP_MS_PRIMARY = 1700000000000L
        private const val TEST_TIME_UNIX_NANO_MULTI_FIRST = 1000000000L
        private const val TEST_TIME_UNIX_NANO_MULTI_SECOND = 2000000000L
        private const val GAUGE_CPU_USAGE_VALUE = 75.5
        private const val GAUGE_THREADS_AS_INT = 42
        private const val GAUGE_MULTI_FIRST_VALUE = 1.0
        private const val GAUGE_MULTI_SECOND_VALUE = 2.0
        private const val SUM_HTTP_REQUESTS_VALUE = 12345.0
        private const val SUM_QUEUE_DEPTH_VALUE = 50.0
        private const val HIST_REQUEST_DURATION_COUNT = 100L
        private const val HIST_REQUEST_DURATION_SUM = 5000.0
        private const val HIST_REQUEST_DURATION_MIN = 5.0
        private const val HIST_REQUEST_DURATION_MAX = 500.0
        private const val EXP_HIST_RPC_COUNT = 200L
        private const val EXP_HIST_RPC_SUM = 10000.0
        private const val EXP_HIST_RPC_MIN = 1.0
        private const val EXP_HIST_RPC_MAX = 800.0
        private const val SUMMARY_GC_COUNT = 50L
        private const val SUMMARY_GC_SUM = 250.5
        private const val DECODE_BATCH_METRIC_VALUE = 42.5
    }

    private lateinit var service: OtlpMetricsService

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "test_db"
        service = OtlpMetricsService()
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    private fun wrapMetric(metricJson: String, resourceAttrs: String = ""): String = """
    {
      "resourceMetrics": [{
        "resource": {
          "attributes": [
            {"key": "service.name", "value": {"stringValue": "$TEST_SVC"}},
            {"key": "deployment.environment", "value": {"stringValue": "$TEST_ENV"}},
            {"key": "host.name", "value": {"stringValue": "$TEST_HOST"}}
            ${if (resourceAttrs.isNotEmpty()) ",$resourceAttrs" else ""}
          ]
        },
        "scopeMetrics": [{
          "metrics": [$metricJson]
        }]
      }]
    }
    """.trimIndent()

    // ──── GAUGE ────

    @Nested
    inner class GaugeMetrics {

        @Test
        fun `parses gauge with asDouble`() {
            val payload = wrapMetric(
                """
            {
              "name": "system.cpu.usage",
              "description": "CPU usage percentage",
              "unit": "%",
              "gauge": {
                "dataPoints": [{
                  "timeUnixNano": $TEST_TIME_UNIX_NANO_PRIMARY,
                  "asDouble": $GAUGE_CPU_USAGE_VALUE,
                  "attributes": [
                    {"key": "cpu.core", "value": {"stringValue": "0"}}
                  ]
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!

            assertEquals(1, metrics.size)
            val m = metrics[0]
            assertEquals("system.cpu.usage", m.metricName)
            assertEquals("gauge", m.metricType)
            assertEquals("CPU usage percentage", m.description)
            assertEquals("%", m.unit)
            assertEquals(TEST_TIMESTAMP_MS_PRIMARY, m.timestampMs)
            assertEquals(GAUGE_CPU_USAGE_VALUE, m.value)
            assertEquals("0", m.tags["cpu.core"])
            assertEquals(TEST_SVC, m.service)
            assertEquals(TEST_ENV, m.env)
            assertEquals(TEST_HOST, m.host)
        }

        @Test
        fun `parses gauge with asInt`() {
            val payload = wrapMetric(
                """
            {
              "name": "process.threads",
              "gauge": {
                "dataPoints": [{
                  "timeUnixNano": $TEST_TIME_UNIX_NANO_PRIMARY,
                  "asInt": $GAUGE_THREADS_AS_INT
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!
            assertEquals(GAUGE_THREADS_AS_INT.toDouble(), metrics[0].value)
        }

        @Test
        fun `parses gauge with multiple data points`() {
            val payload = wrapMetric(
                """
            {
              "name": "multi.gauge",
              "gauge": {
                "dataPoints": [
                  {"timeUnixNano": $TEST_TIME_UNIX_NANO_MULTI_FIRST, "asDouble": $GAUGE_MULTI_FIRST_VALUE},
                  {"timeUnixNano": $TEST_TIME_UNIX_NANO_MULTI_SECOND, "asDouble": $GAUGE_MULTI_SECOND_VALUE}
                ]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!
            assertEquals(2, metrics.size)
            assertEquals(GAUGE_MULTI_FIRST_VALUE, metrics[0].value)
            assertEquals(GAUGE_MULTI_SECOND_VALUE, metrics[1].value)
        }
    }

    // ──── SUM ────

    @Nested
    inner class SumMetrics {

        @Test
        fun `parses monotonic cumulative sum`() {
            val payload = wrapMetric(
                """
            {
              "name": "http.requests.total",
              "description": "Total HTTP requests",
              "sum": {
                "isMonotonic": true,
                "aggregationTemporality": 2,
                "dataPoints": [{
                  "timeUnixNano": $TEST_TIME_UNIX_NANO_PRIMARY,
                  "asDouble": $SUM_HTTP_REQUESTS_VALUE,
                  "attributes": [
                    {"key": "http.method", "value": {"stringValue": "GET"}}
                  ]
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!

            assertEquals(1, metrics.size)
            val m = metrics[0]
            assertEquals("http.requests.total", m.metricName)
            assertEquals("sum", m.metricType)
            assertEquals(SUM_HTTP_REQUESTS_VALUE, m.value)
            assertEquals(1, m.isMonotonic)
            assertEquals("cumulative", m.aggregationTemporality)
            assertEquals("GET", m.tags["http.method"])
        }

        @Test
        fun `parses non-monotonic delta sum`() {
            val payload = wrapMetric(
                """
            {
              "name": "queue.depth",
              "sum": {
                "isMonotonic": false,
                "aggregationTemporality": 1,
                "dataPoints": [{
                  "timeUnixNano": $TEST_TIME_UNIX_NANO_PRIMARY,
                  "asDouble": $SUM_QUEUE_DEPTH_VALUE
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!

            assertEquals(0, metrics[0].isMonotonic)
            assertEquals("delta", metrics[0].aggregationTemporality)
        }
    }

    // ──── HISTOGRAM ────

    @Nested
    inner class HistogramMetrics {

        @Test
        fun `parses histogram with bucket counts and bounds`() {
            val payload = wrapMetric(
                """
            {
              "name": "http.request.duration",
              "description": "Request duration histogram",
              "unit": "ms",
              "histogram": {
                "aggregationTemporality": 2,
                "dataPoints": [{
                  "timeUnixNano": $TEST_TIME_UNIX_NANO_PRIMARY,
                  "count": $HIST_REQUEST_DURATION_COUNT,
                  "sum": $HIST_REQUEST_DURATION_SUM,
                  "min": $HIST_REQUEST_DURATION_MIN,
                  "max": $HIST_REQUEST_DURATION_MAX,
                  "bucketCounts": [10, 30, 40, 15, 5],
                  "explicitBounds": [10.0, 50.0, 100.0, 250.0],
                  "attributes": [
                    {"key": "http.route", "value": {"stringValue": "/api/users"}}
                  ]
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!

            assertEquals(1, metrics.size)
            val m = metrics[0]
            assertEquals("http.request.duration", m.metricName)
            assertEquals("histogram", m.metricType)
            assertEquals("cumulative", m.aggregationTemporality)
            assertEquals(HIST_REQUEST_DURATION_COUNT, m.histCount)
            assertEquals(HIST_REQUEST_DURATION_SUM, m.histSum)
            assertEquals(HIST_REQUEST_DURATION_MIN, m.histMin)
            assertEquals(HIST_REQUEST_DURATION_MAX, m.histMax)
            assertEquals(listOf(10L, 30L, 40L, 15L, 5L), m.histBucketCounts)
            assertEquals(listOf(10.0, 50.0, 100.0, 250.0), m.histExplicitBounds)
            assertEquals(HIST_REQUEST_DURATION_SUM, m.value)
            assertEquals("/api/users", m.tags["http.route"])
        }
    }

    // ──── EXPONENTIAL HISTOGRAM ────

    @Nested
    inner class ExpHistogramMetrics {

        @Test
        fun `parses exponential histogram`() {
            val payload = wrapMetric(
                """
            {
              "name": "rpc.latency",
              "exponentialHistogram": {
                "aggregationTemporality": 1,
                "dataPoints": [{
                  "timeUnixNano": $TEST_TIME_UNIX_NANO_PRIMARY,
                  "count": $EXP_HIST_RPC_COUNT,
                  "sum": $EXP_HIST_RPC_SUM,
                  "min": $EXP_HIST_RPC_MIN,
                  "max": $EXP_HIST_RPC_MAX
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!

            assertEquals(1, metrics.size)
            val m = metrics[0]
            assertEquals("rpc.latency", m.metricName)
            assertEquals("exp_histogram", m.metricType)
            assertEquals("delta", m.aggregationTemporality)
            assertEquals(EXP_HIST_RPC_COUNT, m.histCount)
            assertEquals(EXP_HIST_RPC_SUM, m.histSum)
            assertEquals(EXP_HIST_RPC_MIN, m.histMin)
            assertEquals(EXP_HIST_RPC_MAX, m.histMax)
            assertTrue(m.histBucketCounts.isEmpty())
            assertTrue(m.histExplicitBounds.isEmpty())
        }
    }

    // ──── SUMMARY ────

    @Nested
    inner class SummaryMetrics {

        @Test
        fun `parses summary`() {
            val payload = wrapMetric(
                """
            {
              "name": "process.runtime.gc.pause",
              "summary": {
                "dataPoints": [{
                  "timeUnixNano": $TEST_TIME_UNIX_NANO_PRIMARY,
                  "count": $SUMMARY_GC_COUNT,
                  "sum": $SUMMARY_GC_SUM
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!

            assertEquals(1, metrics.size)
            val m = metrics[0]
            assertEquals("process.runtime.gc.pause", m.metricName)
            assertEquals("summary", m.metricType)
            assertEquals(SUMMARY_GC_COUNT, m.histCount)
            assertEquals(SUMMARY_GC_SUM, m.histSum)
            assertEquals(SUMMARY_GC_SUM, m.value)
        }
    }

    // ──── MIXED METRICS IN ONE PAYLOAD ────

    @Test
    fun `parses multiple metric types in a single payload`() {
        val payload = """
        {
          "resourceMetrics": [{
            "resource": {"attributes": []},
            "scopeMetrics": [{
              "metrics": [
                {
                  "name": "gauge-metric",
                  "gauge": {"dataPoints": [{"timeUnixNano": 0, "asDouble": 1.0}]}
                },
                {
                  "name": "sum-metric",
                  "sum": {"dataPoints": [{"timeUnixNano": 0, "asDouble": 2.0}]}
                },
                {
                  "name": "hist-metric",
                  "histogram": {"dataPoints": [{"timeUnixNano": 0, "count": 10, "sum": 100.0}]}
                }
              ]
            }]
          }]
        }
        """.trimIndent()

        val metrics = service.parseOtlpMetricsJson(payload)!!

        assertEquals(3, metrics.size)
        assertEquals("gauge", metrics[0].metricType)
        assertEquals("sum", metrics[1].metricType)
        assertEquals("histogram", metrics[2].metricType)
    }

    // ──── LEGACY FIELD NAME ────

    @Test
    fun `supports instrumentationLibraryMetrics as legacy field`() {
        val payload = """
        {
          "resourceMetrics": [{
            "resource": {},
            "instrumentationLibraryMetrics": [{
              "metrics": [{
                "name": "legacy.metric",
                "gauge": {"dataPoints": [{"timeUnixNano": 0, "asDouble": 99.0}]}
              }]
            }]
          }]
        }
        """.trimIndent()

        val metrics = service.parseOtlpMetricsJson(payload)!!
        assertEquals(1, metrics.size)
        assertEquals("legacy.metric", metrics[0].metricName)
    }

    // ──── EMPTY / INVALID PAYLOADS ────

    @Test
    fun `returns empty list for valid payload with empty resourceMetrics`() {
        val result = service.parseOtlpMetricsJson("""{"resourceMetrics": []}""")
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns null for missing resourceMetrics`() {
        assertNull(service.parseOtlpMetricsJson("""{"other": true}"""))
    }

    @Test
    fun `returns null for invalid JSON`() {
        assertNull(service.parseOtlpMetricsJson("not-json"))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(service.parseOtlpMetricsJson(""))
    }

    // ──── DEFAULTS FOR MISSING FIELDS ────

    @Test
    fun `defaults for gauge with no attributes or description`() {
        val payload = wrapMetric(
            """
        {
          "name": "simple",
          "gauge": {
            "dataPoints": [{"timeUnixNano": 0, "asDouble": 0.0}]
          }
        }
            """.trimIndent()
        )

        val metrics = service.parseOtlpMetricsJson(payload)!!
        val m = metrics[0]
        assertEquals("", m.description)
        assertEquals("", m.unit)
        assertTrue(m.tags.isEmpty())
        assertEquals(0, m.isMonotonic)
        assertEquals("", m.aggregationTemporality)
    }

    // ──── BATCH DECODE ROUNDTRIP ────

    @Test
    fun `decodeBatch roundtrips correctly`() {
        val batch = QueuedOtlpMetricsBatch(
            organizationId = 7L,
            metrics = listOf(
                OtlpMetricInsert(
                    organizationId = 7L,
                    metricName = "cpu.usage",
                    metricType = "gauge",
                    description = "CPU",
                    unit = "%",
                    timestampMs = TEST_TIMESTAMP_MS_PRIMARY,
                    value = DECODE_BATCH_METRIC_VALUE,
                    isMonotonic = 0,
                    aggregationTemporality = "",
                    histCount = 0,
                    histSum = null,
                    histMin = null,
                    histMax = null,
                    histBucketCounts = emptyList(),
                    histExplicitBounds = emptyList(),
                    tags = mapOf("env" to TEST_ENV),
                    resourceAttributes = mapOf("service.name" to TEST_SVC),
                    service = TEST_SVC,
                    env = TEST_ENV,
                    host = TEST_HOST,
                )
            )
        )

        val encoded = OtlpMetricsService.queuedBatchJson.encodeToString(batch)
        val decoded = service.decodeBatch(encoded)

        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals(1, decoded.metrics.size)
        assertEquals("cpu.usage", decoded.metrics[0].metricName)
        assertEquals(DECODE_BATCH_METRIC_VALUE, decoded.metrics[0].value)
        assertEquals(TEST_ENV, decoded.metrics[0].tags["env"])
    }
}
