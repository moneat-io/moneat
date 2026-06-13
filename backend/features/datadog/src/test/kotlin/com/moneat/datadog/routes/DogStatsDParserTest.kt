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

package com.moneat.datadog.routes

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DogStatsDParserTest {

    @Test
    fun `parses basic gauge metric`() {
        val result = parseDogStatsDLine("page.views:100|g")
        assertEquals("page.views", result?.metric)
        assertEquals("gauge", result?.type)
        assertEquals(100.0, result?.points?.get(0)?.get(1))
    }

    @Test
    fun `parses count metric`() {
        val result = parseDogStatsDLine("requests:1|c")
        assertEquals("requests", result?.metric)
        assertEquals("count", result?.type)
    }

    @Test
    fun `parses rate metric`() {
        val result = parseDogStatsDLine("throughput:5.5|r")
        assertEquals("throughput", result?.metric)
        assertEquals("rate", result?.type)
        assertEquals(5.5, result?.points?.get(0)?.get(1))
    }

    @Test
    fun `parses metric with tags`() {
        val result = parseDogStatsDLine("cpu:42.5|g|#env:prod,service:web")
        assertNotNull(result)
        assertEquals("cpu", result.metric)
        assertEquals(42.5, result.points[0][1])
        assertEquals(2, result.tags.size)
        assertTrue(result.tags.contains("env:prod"))
        assertTrue(result.tags.contains("service:web"))
    }

    @Test
    fun `returns null for invalid format`() {
        assertNull(parseDogStatsDLine("invalid"))
        assertNull(parseDogStatsDLine(""))
        assertNull(parseDogStatsDLine("metric:notanumber|g"))
    }

    @Test
    fun `returns null for missing type`() {
        assertNull(parseDogStatsDLine("metric:100"))
    }

    @Test
    fun `parses multiple lines`() {
        val input = """
            cpu:42.5|g|#host:web-01
            mem:80.0|g|#host:web-01
            requests:1|c
        """.trimIndent()

        val results = parseDogStatsDLines(input)
        assertEquals(3, results.size)
        assertEquals("cpu", results[0].metric)
        assertEquals("mem", results[1].metric)
        assertEquals("requests", results[2].metric)
    }

    @Test
    fun `parses lines skips blank lines`() {
        val input = "cpu:42.5|g\n\nmem:80.0|g\n\n"
        val results = parseDogStatsDLines(input)
        assertEquals(2, results.size)
    }

    @Test
    fun `parses histogram type as gauge`() {
        val result = parseDogStatsDLine("latency:250|h")
        assertEquals("gauge", result?.type)
    }

    @Test
    fun `parses distribution type as gauge`() {
        val result = parseDogStatsDLine("latency:250|d")
        assertEquals("gauge", result?.type)
    }

    @Test
    fun `parses timer type as gauge`() {
        val result = parseDogStatsDLine("render_time:320|ms")
        assertEquals("gauge", result?.type)
    }

    @Test
    fun `parses negative values`() {
        val result = parseDogStatsDLine("temp:-15.5|g")
        assertEquals(-15.5, result?.points?.get(0)?.get(1))
    }

    @Test
    fun `parses decimal values`() {
        val result = parseDogStatsDLine("cpu:99.99|g")
        assertEquals(99.99, result?.points?.get(0)?.get(1))
    }
}
