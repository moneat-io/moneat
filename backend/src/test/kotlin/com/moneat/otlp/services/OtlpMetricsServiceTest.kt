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
import kotlinx.serialization.json.Json
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
    }

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "test_db"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    private val service by lazy { OtlpMetricsService() }

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
                  "timeUnixNano": 1700000000000000000,
                  "asDouble": 75.5,
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
            assertEquals(1700000000000L, m.timestampMs)
            assertEquals(75.5, m.value)
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
                  "timeUnixNano": 1700000000000000000,
                  "asInt": 42
                }]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!
            assertEquals(42.0, metrics[0].value)
        }

        @Test
        fun `parses gauge with multiple data points`() {
            val payload = wrapMetric(
                """
            {
              "name": "multi.gauge",
              "gauge": {
                "dataPoints": [
                  {"timeUnixNano": 1000000000, "asDouble": 1.0},
                  {"timeUnixNano": 2000000000, "asDouble": 2.0}
                ]
              }
            }
                """.trimIndent()
            )

            val metrics = service.parseOtlpMetricsJson(payload)!!
            assertEquals(2, metrics.size)
            assertEquals(1.0, metrics[0].value)
            assertEquals(2.0, metrics[1].value)
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
                  "timeUnixNano": 1700000000000000000,
                  "asDouble": 12345.0,
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
            assertEquals(12345.0, m.value)
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
                  "timeUnixNano": 1700000000000000000,
                  "asDouble": 50.0
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
                  "timeUnixNano": 1700000000000000000,
                  "count": 100,
                  "sum": 5000.0,
                  "min": 5.0,
                  "max": 500.0,
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
            assertEquals(100L, m.histCount)
            assertEquals(5000.0, m.histSum)
            assertEquals(5.0, m.histMin)
            assertEquals(500.0, m.histMax)
            assertEquals(listOf(10L, 30L, 40L, 15L, 5L), m.histBucketCounts)
            assertEquals(listOf(10.0, 50.0, 100.0, 250.0), m.histExplicitBounds)
            assertEquals(5000.0, m.value)
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
                  "timeUnixNano": 1700000000000000000,
                  "count": 200,
                  "sum": 10000.0,
                  "min": 1.0,
                  "max": 800.0
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
            assertEquals(200L, m.histCount)
            assertEquals(10000.0, m.histSum)
            assertEquals(1.0, m.histMin)
            assertEquals(800.0, m.histMax)
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
                  "timeUnixNano": 1700000000000000000,
                  "count": 50,
                  "sum": 250.5
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
            assertEquals(50L, m.histCount)
            assertEquals(250.5, m.histSum)
            assertEquals(250.5, m.value)
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
                    timestampMs = 1700000000000L,
                    value = 42.5,
                    isMonotonic = 0,
                    aggregationTemporality = "",
                    histCount = 0,
                    histSum = null,
                    histMin = null,
                    histMax = null,
                    histBucketCounts = emptyList(),
                    histExplicitBounds = emptyList(),
                    tags = mapOf("env" to "prod"),
                    resourceAttributes = mapOf("service.name" to TEST_SVC),
                    service = TEST_SVC,
                    env = TEST_ENV,
                    host = TEST_HOST,
                )
            )
        )

        val encoded = Json.encodeToString(batch)
        val decoded = service.decodeBatch(encoded)

        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals(1, decoded.metrics.size)
        assertEquals("cpu.usage", decoded.metrics[0].metricName)
        assertEquals(42.5, decoded.metrics[0].value)
        assertEquals("prod", decoded.metrics[0].tags["env"])
    }
}
