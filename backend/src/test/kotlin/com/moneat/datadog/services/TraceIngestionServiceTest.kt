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

import com.moneat.datadog.models.DdSpan
import org.junit.jupiter.api.Test
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessagePack
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceIngestionServiceTest {

    // ============ MSGPACK PARSING TESTS ============

    @Test
    fun `parseMsgpackTraces parses single trace with one span`() {
        val bytes = buildMsgpackPayload(
            listOf(
                listOf(
                    buildSpanMap(
                        traceId = 123L,
                        spanId = 456L,
                        parentId = 0L,
                        name = "web.request",
                        service = "my-app",
                        resource = "GET /api/v1/users",
                        type = "web",
                        start = 1700000000000000000L,
                        duration = 50000000L,
                        error = 0,
                    )
                )
            )
        )

        val result = TraceIngestionService.parseMsgpackTraces(bytes)

        assertEquals(1, result.size, "Should parse 1 trace")
        assertEquals(1, result[0].size, "Trace should have 1 span")

        val span = result[0][0]
        assertEquals(123uL, span.traceId)
        assertEquals(456uL, span.spanId)
        assertEquals(0uL, span.parentId)
        assertEquals("web.request", span.name)
        assertEquals("my-app", span.service)
        assertEquals("GET /api/v1/users", span.resource)
        assertEquals("web", span.type)
        assertEquals(1700000000000000000L, span.start)
        assertEquals(50000000L, span.duration)
        assertEquals(0, span.error)
    }

    @Test
    fun `parseMsgpackTraces parses multiple traces with multiple spans`() {
        val bytes = buildMsgpackPayload(
            listOf(
                listOf(
                    buildSpanMap(traceId = 1L, spanId = 10L, name = "root"),
                    buildSpanMap(
                        traceId = 1L, spanId = 11L,
                        parentId = 10L, name = "child"
                    ),
                ),
                listOf(
                    buildSpanMap(traceId = 2L, spanId = 20L, name = "other"),
                )
            )
        )

        val result = TraceIngestionService.parseMsgpackTraces(bytes)

        assertEquals(2, result.size, "Should parse 2 traces")
        assertEquals(2, result[0].size, "First trace should have 2 spans")
        assertEquals(1, result[1].size, "Second trace should have 1 span")
        assertEquals("root", result[0][0].name)
        assertEquals("child", result[0][1].name)
        assertEquals(10uL, result[0][1].parentId)
    }

    @Test
    fun `parseMsgpackTraces handles span with meta and metrics`() {
        val bytes = buildMsgpackPayload(
            listOf(
                listOf(
                    buildSpanMap(
                        traceId = 100L,
                        spanId = 200L,
                        name = "db.query",
                        meta = mapOf(
                            "db.type" to "postgresql",
                            "db.statement" to "SELECT * FROM users"
                        ),
                        metrics = mapOf(
                            "_dd.measured" to 1.0,
                            "_sampling_priority_v1" to 2.0
                        ),
                    )
                )
            )
        )

        val result = TraceIngestionService.parseMsgpackTraces(bytes)
        val span = result[0][0]

        assertEquals(
            "postgresql", span.meta["db.type"],
            "Should parse meta map"
        )
        assertEquals(
            "SELECT * FROM users", span.meta["db.statement"]
        )
        assertEquals(1.0, span.metrics["_dd.measured"])
        assertEquals(2.0, span.metrics["_sampling_priority_v1"])
    }

    @Test
    fun `parseMsgpackTraces handles span with error flag`() {
        val bytes = buildMsgpackPayload(
            listOf(
                listOf(
                    buildSpanMap(
                        traceId = 1L, spanId = 1L,
                        name = "failing.op", error = 1,
                        meta = mapOf(
                            "error.msg" to "NullPointerException"
                        )
                    )
                )
            )
        )

        val result = TraceIngestionService.parseMsgpackTraces(bytes)
        assertEquals(1, result[0][0].error, "Error flag should be 1")
        assertEquals(
            "NullPointerException",
            result[0][0].meta["error.msg"]
        )
    }

    @Test
    fun `parseMsgpackTraces handles empty payload`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(0)
        packer.close()

        val result = TraceIngestionService.parseMsgpackTraces(
            packer.toByteArray()
        )
        assertTrue(result.isEmpty(), "Empty payload should produce no traces")
    }

    @Test
    fun `parseMsgpackTraces skips unknown fields`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(1) // 1 trace
        packer.packArrayHeader(1) // 1 span
        packer.packMapHeader(4) // 4 fields

        packer.packString("trace_id")
        packer.packLong(999L)
        packer.packString("span_id")
        packer.packLong(888L)
        packer.packString("name")
        packer.packString("test.op")
        packer.packString("unknown_custom_field")
        packer.packString("should_be_skipped")

        packer.close()

        val result = TraceIngestionService.parseMsgpackTraces(
            packer.toByteArray()
        )

        assertEquals(999uL, result[0][0].traceId)
        assertEquals("test.op", result[0][0].name)
    }

    // ============ JSON PARSING TESTS ============

    @Test
    fun `parseJsonTraces parses single trace with one span`() {
        val json = """
            [[{
                "trace_id": 123,
                "span_id": 456,
                "parent_id": 0,
                "name": "web.request",
                "service": "my-app",
                "resource": "GET /users",
                "type": "web",
                "start": 1700000000000000000,
                "duration": 50000000,
                "error": 0,
                "meta": {"http.method": "GET"},
                "metrics": {"_dd.measured": 1.0}
            }]]
        """.trimIndent()

        val result = TraceIngestionService.parseJsonTraces(json)

        assertEquals(1, result.size)
        assertEquals(1, result[0].size)

        val span = result[0][0]
        assertEquals(123uL, span.traceId)
        assertEquals(456uL, span.spanId)
        assertEquals("web.request", span.name)
        assertEquals("my-app", span.service)
        assertEquals("GET", span.meta["http.method"])
        assertEquals(1.0, span.metrics["_dd.measured"])
    }

    @Test
    fun `parseJsonTraces parses multiple traces`() {
        val json = """
            [
              [
                {"trace_id": 1, "span_id": 10, "name": "a"},
                {"trace_id": 1, "span_id": 11, "parent_id": 10, "name": "b"}
              ],
              [
                {"trace_id": 2, "span_id": 20, "name": "c"}
              ]
            ]
        """.trimIndent()

        val result = TraceIngestionService.parseJsonTraces(json)

        assertEquals(2, result.size)
        assertEquals(2, result[0].size)
        assertEquals(1, result[1].size)
        assertEquals("a", result[0][0].name)
        assertEquals("b", result[0][1].name)
        assertEquals("c", result[1][0].name)
    }

    @Test
    fun `parseJsonTraces handles missing optional fields gracefully`() {
        val json = """
            [[{
                "trace_id": 1,
                "span_id": 2,
                "name": "minimal"
            }]]
        """.trimIndent()

        val result = TraceIngestionService.parseJsonTraces(json)
        val span = result[0][0]

        assertEquals(1uL, span.traceId)
        assertEquals(2uL, span.spanId)
        assertEquals("minimal", span.name)
        assertEquals("", span.service)
        assertEquals("", span.resource)
        assertEquals("", span.type)
        assertEquals(0L, span.start)
        assertEquals(0L, span.duration)
        assertEquals(0, span.error)
        assertTrue(span.meta.isEmpty())
        assertTrue(span.metrics.isEmpty())
    }

    @Test
    fun `parseJsonTraces handles empty trace array`() {
        val json = "[]"
        val result = TraceIngestionService.parseJsonTraces(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseTraceId accepts unsigned numeric values`() {
        val parsed = TraceIngestionService.parseTraceId("18446744073709551615")
        assertEquals(ULong.MAX_VALUE, parsed)
    }

    @Test
    fun `parseTraceId rejects non numeric input`() {
        assertEquals(null, TraceIngestionService.parseTraceId("0 OR 1=1"))
        assertEquals(null, TraceIngestionService.parseTraceId("abc"))
    }

    // ============ HELPERS ============

    private fun buildMsgpackPayload(
        traces: List<List<Map<String, Any>>>
    ): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(traces.size)
        for (trace in traces) {
            packer.packArrayHeader(trace.size)
            for (spanMap in trace) {
                packSpanMap(packer, spanMap)
            }
        }
        packer.close()
        return packer.toByteArray()
    }

    private fun buildSpanMap(
        traceId: Long = 0L,
        spanId: Long = 0L,
        parentId: Long = 0L,
        name: String = "",
        service: String = "",
        resource: String = "",
        type: String = "",
        start: Long = 0L,
        duration: Long = 0L,
        error: Int = 0,
        meta: Map<String, String> = emptyMap(),
        metrics: Map<String, Double> = emptyMap(),
    ): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "trace_id" to traceId,
            "span_id" to spanId,
            "parent_id" to parentId,
            "name" to name,
            "service" to service,
            "resource" to resource,
            "type" to type,
            "start" to start,
            "duration" to duration,
            "error" to error,
        )
        if (meta.isNotEmpty()) map["meta"] = meta
        if (metrics.isNotEmpty()) map["metrics"] = metrics
        return map
    }

    @Suppress("CyclomaticComplexity")
    private fun packSpanMap(
        packer: MessageBufferPacker,
        spanMap: Map<String, Any>
    ) {
        packer.packMapHeader(spanMap.size)
        for ((key, value) in spanMap) {
            packer.packString(key)
            when (value) {
                is Long -> packer.packLong(value)
                is Int -> packer.packInt(value)
                is String -> packer.packString(value)
                is Map<*, *> -> {
                    packer.packMapHeader(value.size)
                    for ((mk, mv) in value) {
                        packer.packString(mk as String)
                        when (mv) {
                            is String -> packer.packString(mv)
                            is Double -> packer.packDouble(mv)
                            else -> packer.packString(mv.toString())
                        }
                    }
                }
                else -> packer.packString(value.toString())
            }
        }
    }
}
