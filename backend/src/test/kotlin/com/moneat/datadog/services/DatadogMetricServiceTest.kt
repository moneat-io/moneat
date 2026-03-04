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

import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogMetricV1
import com.moneat.datadog.models.DatadogSketchPayload
import com.moneat.datadog.models.DatadogSketchPoint
import com.moneat.datadog.models.DatadogSketch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatadogMetricServiceTest {

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
        assertEquals(1, batch.metrics.size)
    }

    @Test
    fun `mapV1Series handles empty series`() {
        val payload = DatadogMetricSeriesV1(series = emptyList())
        val batch = DatadogMetricService.mapV1Series(1L, payload)
        assertEquals(0, batch.metrics.size)
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

        val batch = DatadogMetricService.mapSketches(42L, payload)

        assertEquals(42L, batch.organizationId)
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
}
