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

package com.moneat.datadog.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogMetricV1
import com.moneat.datadog.models.DatadogSketch
import com.moneat.datadog.models.DatadogSketchPayload
import com.moneat.datadog.models.DatadogSketchPoint
import com.moneat.monitoring.OperationalMetrics
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatadogMetricServiceTest {

    @BeforeEach
    fun resetMetricsBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterEach
    fun resetMetricsAfter() {
        OperationalMetrics.resetForTest()
    }

    @Test
    fun `parseDdTagList parses key-value pairs`() {
        val tags = DatadogMetricService.parseDdTagList(
            listOf("env:prod", "service:web", "version:1.0")
        )
        assertEquals("prod", tags["env"])
        assertEquals("web", tags["service"])
        assertEquals("1.0", tags["version"])
    }

    @Test
    fun `parseDdTagList handles tags without values`() {
        val tags = DatadogMetricService.parseDdTagList(
            listOf("standalone", "env:prod")
        )
        assertEquals("", tags["standalone"])
        assertEquals("prod", tags["env"])
    }

    @Test
    fun `parseDdTagList handles empty list`() {
        val tags = DatadogMetricService.parseDdTagList(emptyList())
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `flattenV1Points extracts timestamp and value`() {
        val series = DatadogMetricV1(
            metric = "system.cpu.user",
            type = "gauge",
            host = "web-01",
            tags = listOf("env:prod"),
            points = listOf(
                listOf(1700000000.0, 42.5),
                listOf(1700000010.0, 43.0)
            )
        )

        val entries = DatadogMetricService.flattenV1Points(series)

        assertEquals(2, entries.size)
        assertEquals("system.cpu.user", entries[0].name)
        assertEquals("gauge", entries[0].type)
        assertEquals(1700000000000L, entries[0].timestampMs)
        assertEquals(42.5, entries[0].value)
        assertEquals("web-01", entries[0].host)
        assertEquals("prod", entries[0].tags["env"])
    }

    @Test
    fun `flattenV1Points skips points with less than 2 elements`() {
        val series = DatadogMetricV1(
            metric = "test",
            points = listOf(
                listOf(1700000000.0),
                listOf(1700000010.0, 42.0)
            )
        )

        val entries = DatadogMetricService.flattenV1Points(series)
        assertEquals(1, entries.size)
    }

    @Test
    fun `flattenV1Points normalizes metric types`() {
        val gaugeEntries = DatadogMetricService.flattenV1Points(
            DatadogMetricV1(
                metric = "test",
                type = "gauge",
                points = listOf(listOf(0.0, 1.0))
            )
        )
        assertEquals("gauge", gaugeEntries[0].type)

        val countEntries = DatadogMetricService.flattenV1Points(
            DatadogMetricV1(
                metric = "test",
                type = "count",
                points = listOf(listOf(0.0, 1.0))
            )
        )
        assertEquals("count", countEntries[0].type)

        val rateEntries = DatadogMetricService.flattenV1Points(
            DatadogMetricV1(
                metric = "test",
                type = "rate",
                points = listOf(listOf(0.0, 1.0))
            )
        )
        assertEquals("rate", rateEntries[0].type)

        val unknownEntries = DatadogMetricService.flattenV1Points(
            DatadogMetricV1(
                metric = "test",
                type = "histogram",
                points = listOf(listOf(0.0, 1.0))
            )
        )
        assertEquals("gauge", unknownEntries[0].type)
    }

    @Test
    fun `mapV1Series creates batch with correct org id`() {
        val payload = DatadogMetricSeriesV1(
            series = listOf(
                DatadogMetricV1(
                    metric = "cpu",
                    points = listOf(listOf(0.0, 50.0))
                )
            )
        )

        val batch = DatadogMetricService.mapV1Series(42L, payload)
        assertEquals(42L, batch.organizationId)
        assertNull(batch.projectId)
        assertEquals(1, batch.metrics.size)
    }

    @Test
    fun `mapV1Series preserves project id`() {
        val payload = DatadogMetricSeriesV1(
            series = listOf(
                DatadogMetricV1(
                    metric = "cpu",
                    points = listOf(listOf(0.0, 50.0))
                )
            )
        )

        val batch = DatadogMetricService.mapV1Series(42L, payload, projectId = 7L)

        assertEquals(7L, batch.projectId)
    }

    @Test
    fun `mapV1Series handles empty series`() {
        val payload = DatadogMetricSeriesV1(series = emptyList())
        val batch = DatadogMetricService.mapV1Series(1L, payload)
        assertEquals(0, batch.metrics.size)
    }

    @Test
    fun `enqueueMetrics serializes project id in queued batch`() = runBlocking {
        val redis = mockk<RedisCommands<String, String>>()
        val queuedBody = slot<Map<String, String>>()
        val payload = DatadogMetricSeriesV1(
            series = listOf(
                DatadogMetricV1(
                    metric = "cpu",
                    points = listOf(listOf(0.0, 50.0))
                )
            )
        )

        mockkObject(RedisConfig)
        try {
            every { RedisConfig.sync() } returns redis
            every { redis.xadd("test:dd:metric:queue:stream", any<XAddArgs>(), capture(queuedBody)) } returns "1-0"

            val count = DatadogMetricService.enqueueMetrics(
                organizationId = 42L,
                payload = payload,
                projectId = 7L,
                queueKey = "test:dd:metric:queue",
            )

            val batch = DatadogMetricService.decodeMetricBatch(requireNotNull(queuedBody.captured["payload"]))
            assertEquals(1, count)
            assertEquals(42L, batch.organizationId)
            assertEquals(7L, batch.projectId)

            val rendered = OperationalMetrics.scrape()
            assertContains(rendered, "moneat_datadog_metric_payloads_queued_total")
            assertContains(rendered, "moneat_datadog_metric_points_queued_total")
        } finally {
            unmockkObject(RedisConfig)
        }
    }

    @Test
    fun `mapSketches maps distributions correctly`() {
        val payload = DatadogSketchPayload(
            sketches = listOf(
                DatadogSketch(
                    metric = "latency",
                    host = "web-01",
                    tags = listOf("env:prod"),
                    distributions = listOf(
                        DatadogSketchPoint(
                            ts = 1700000000,
                            cnt = 100,
                            min = 1.0,
                            max = 500.0,
                            avg = 50.0,
                            sum = 5000.0,
                            k = listOf(1, 2, 3),
                            n = listOf(10, 20, 70)
                        )
                    )
                )
            )
        )

        val batch = DatadogMetricService.mapSketches(42L, payload, projectId = 7L)

        assertEquals(42L, batch.organizationId)
        assertEquals(7L, batch.projectId)
        assertEquals(1, batch.sketches.size)

        val sketch = batch.sketches[0]
        assertEquals("latency", sketch.name)
        assertEquals(1700000000000L, sketch.timestampMs)
        assertEquals("web-01", sketch.host)
        assertEquals("prod", sketch.tags["env"])
        assertEquals(100L, sketch.count)
        assertEquals(1.0, sketch.min)
        assertEquals(500.0, sketch.max)
        assertEquals(50.0, sketch.avg)
        assertEquals(5000.0, sketch.sum)
        assertEquals(listOf(1, 2, 3), sketch.k)
        assertEquals(listOf(10, 20, 70), sketch.n)
    }

    @Test
    fun `decodeMetricBatch roundtrips correctly`() {
        val batch = QueuedMetricBatch(
            organizationId = 1L,
            metrics = listOf(
                QueuedMetricEntry(
                    name = "cpu",
                    type = "gauge",
                    timestampMs = 1700000000000L,
                    value = 42.5,
                    host = "web-01",
                    tags = mapOf("env" to "prod")
                )
            )
        )

        val encoded = kotlinx.serialization.json.Json.encodeToString(batch)
        val decoded = DatadogMetricService.decodeMetricBatch(encoded)

        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals(batch.metrics.size, decoded.metrics.size)
        assertEquals(batch.metrics[0].name, decoded.metrics[0].name)
        assertEquals(batch.metrics[0].value, decoded.metrics[0].value)
    }

    @Test
    fun `insertMetricBatch writes raw metrics as JSONEachRow with UTC millis and JSON tags`() = runBlocking {
        val queries = mutableListOf<String>()
        val response = mockk<HttpResponse>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "test_db"
            every { response.status } returns HttpStatusCode.OK
            coEvery { ClickHouseClient.execute(capture(queries)) } returns response

            DatadogMetricService.insertMetricBatch(
                QueuedMetricBatch(
                    organizationId = 42L,
                    metrics = listOf(
                        QueuedMetricEntry(
                            name = "custom.metric",
                            type = "gauge",
                            timestampMs = 1_700_000_000_123L,
                            value = 42.5,
                            host = "web-01",
                            tags = mapOf("odd" to "O'Brien \"prod\"\nline"),
                            unit = "%",
                            sourceTypeName = "agent",
                        )
                    )
                )
            )

            val query = queries.single { it.contains("INSERT INTO `test_db`.metrics ") }
            assertTrue(query.contains("FORMAT JSONEachRow"))
            assertFalse(query.contains("VALUES"))
            assertFalse(query.contains("fromUnixTimestamp64Milli"))
            assertFalse(query.contains("map("))

            val row = jsonRows(query).single()
            assertNull(row["metric_id"])
            assertEquals("42", row["organization_id"]?.jsonPrimitive?.content)
            assertEquals("0", row["service_id"]?.jsonPrimitive?.content)
            assertEquals("0", row["project_id"]?.jsonPrimitive?.content)
            assertEquals("2023-11-14 22:13:20.123", row["timestamp"]?.jsonPrimitive?.content)
            assertEquals("O'Brien \"prod\"\nline", row["tags"]?.jsonObject?.get("odd")?.jsonPrimitive?.content)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `insertMetricBatch writes non-zero project id in JSONEachRow`() = runBlocking {
        val queries = mutableListOf<String>()
        val response = mockk<HttpResponse>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "test_db"
            every { response.status } returns HttpStatusCode.OK
            coEvery { ClickHouseClient.execute(capture(queries)) } returns response

            DatadogMetricService.insertMetricBatch(
                QueuedMetricBatch(
                    organizationId = 42L,
                    projectId = 7L,
                    metrics = listOf(
                        QueuedMetricEntry(
                            name = "custom.metric",
                            type = "gauge",
                            timestampMs = 1_700_000_000_123L,
                            value = 42.5,
                        )
                    )
                )
            )

            val query = queries.single { it.contains("INSERT INTO `test_db`.metrics ") }
            val row = jsonRows(query).single()
            assertEquals("7", row["service_id"]?.jsonPrimitive?.content)
            assertEquals("7", row["project_id"]?.jsonPrimitive?.content)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `insertSketchBatch writes non-zero project id`() = runBlocking {
        val queries = mutableListOf<String>()
        val response = mockk<HttpResponse>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "test_db"
            every { response.status } returns HttpStatusCode.OK
            coEvery { ClickHouseClient.execute(capture(queries)) } returns response

            DatadogMetricService.insertSketchBatch(
                QueuedSketchBatch(
                    organizationId = 42L,
                    sketches = listOf(
                        QueuedSketchEntry(
                            name = "latency",
                            timestampMs = 1_700_000_000_000L,
                            host = "web-01",
                            tags = mapOf("env" to "prod"),
                            count = 10,
                        )
                    ),
                    projectId = 7L,
                )
            )

            val query = queries.single { it.contains("INSERT INTO `test_db`.metric_sketches ") }
            assertTrue(query.contains("organization_id, service_id, project_id, metric_name"))
            assertTrue(Regex("""(?s)\(\s*42,\s*7,\s*7,\s*'latency'""").containsMatchIn(query))
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `insertMetricBatch writes raw metrics and infra rollups for host metrics`() = runBlocking {
        val queries = mutableListOf<String>()
        val response = mockk<HttpResponse>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "test_db"
            every { response.status } returns HttpStatusCode.OK
            coEvery { ClickHouseClient.execute(capture(queries)) } returns response

            DatadogMetricService.insertMetricBatch(
                QueuedMetricBatch(
                    organizationId = 42L,
                    metrics = listOf(
                        QueuedMetricEntry(
                            name = "system.cpu.percent",
                            type = "gauge",
                            timestampMs = 1_700_000_000_000L,
                            value = 42.5,
                            host = "web-01",
                            tags = mapOf("host_id" to "7"),
                            unit = "%",
                        )
                    )
                )
            )

            assertTrue(queries.any { it.contains("INSERT INTO `test_db`.metrics ") })
            assertTrue(queries.any { it.contains("metrics_latest_by_host") })
            assertTrue(queries.any { it.contains("metrics_rollup_1m") })
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `insertMetricBatch normalizes host tags before raw and rollup writes`() = runBlocking {
        val queries = mutableListOf<String>()
        val response = mockk<HttpResponse>()
        mockkObject(ClickHouseClient)
        mockkObject(DatadogHostService)
        try {
            every { ClickHouseClient.getDatabase() } returns "test_db"
            every { response.status } returns HttpStatusCode.OK
            every { DatadogHostService.resolveHostIds(42, setOf("web-01")) } returns mapOf("web-01" to 7)
            coEvery { ClickHouseClient.execute(capture(queries)) } returns response

            DatadogMetricService.insertMetricBatch(
                QueuedMetricBatch(
                    organizationId = 42L,
                    metrics = listOf(
                        QueuedMetricEntry(
                            name = "system.cpu.user",
                            type = "gauge",
                            timestampMs = 1_700_000_000_000L,
                            value = 23.5,
                            host = "",
                            tags = mapOf("host" to "web-01", "host_id" to ""),
                            unit = "%",
                        )
                    )
                )
            )

            val rawRow = jsonRows(queries.single { it.contains("INSERT INTO `test_db`.metrics ") }).single()
            assertEquals("web-01", rawRow["host"]?.jsonPrimitive?.content)
            assertEquals("7", rawRow["tags"]?.jsonObject?.get("host_id")?.jsonPrimitive?.content)

            val latestRollup = queries.single { it.contains("metrics_latest_by_host") }
            assertTrue(latestRollup.contains("'system.cpu.user'"))
            assertTrue(latestRollup.contains("'web-01'"))
            assertTrue(latestRollup.contains("'host_id', '7'"))
        } finally {
            unmockkObject(DatadogHostService)
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `insertMetricBatch preserves raw insert success when rollup setup fails`() = runBlocking {
        val queries = mutableListOf<String>()
        val response = mockk<HttpResponse>()
        var getDatabaseCalls = 0
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } answers {
                getDatabaseCalls += 1
                if (getDatabaseCalls == 1) {
                    "test_db"
                } else {
                    throw IllegalStateException("rollup database unavailable")
                }
            }
            every { response.status } returns HttpStatusCode.OK
            coEvery { ClickHouseClient.execute(capture(queries)) } returns response

            DatadogMetricService.insertMetricBatch(
                QueuedMetricBatch(
                    organizationId = 42L,
                    metrics = listOf(
                        QueuedMetricEntry(
                            name = "system.cpu.percent",
                            type = "gauge",
                            timestampMs = 1_700_000_000_000L,
                            value = 42.5,
                            host = "web-01",
                            tags = mapOf("host_id" to "7"),
                            unit = "%",
                        )
                    )
                )
            )

            assertEquals(1, queries.size)
            assertTrue(queries.single().contains("INSERT INTO `test_db`.metrics "))
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    private fun jsonRows(query: String) =
        query.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()
}
