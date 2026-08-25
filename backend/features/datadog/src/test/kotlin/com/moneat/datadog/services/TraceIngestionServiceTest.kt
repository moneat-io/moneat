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

import com.google.protobuf.CodedOutputStream
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceIngestionServiceTest {

    @Test
    fun `parseProtobufAgentPayload decodes span fields and map entries`() {
        val stringMeta = buildProto {
            writeString(1, "http.method")
            writeString(2, "GET")
        }
        val doubleMetric = buildProto {
            writeString(1, "sampling.rate")
            writeDouble(2, 0.5)
        }
        val span = buildProto {
            writeString(1, "api")
            writeString(2, "GET /health")
            writeString(3, "/health")
            writeUInt64(4, 101)
            writeUInt64(5, 202)
            writeUInt64(6, 303)
            writeInt64(7, 1_700_000_000_000L)
            writeInt64(8, 42_000L)
            writeInt32(9, 1)
            writeByteArray(10, stringMeta)
            writeByteArray(11, doubleMetric)
            writeString(12, "web")
        }
        val chunk = buildProto { writeByteArray(3, span) }
        val tracerPayload = buildProto { writeByteArray(6, chunk) }
        val agentPayload = buildProto { writeByteArray(5, tracerPayload) }

        val traces = TraceIngestionService.parseProtobufAgentPayload(agentPayload)

        assertEquals(1, traces.size)
        assertEquals(1, traces.single().size)
        val decoded = traces.single().single()
        assertEquals(101uL, decoded.traceId)
        assertEquals(202uL, decoded.spanId)
        assertEquals(303uL, decoded.parentId)
        assertEquals("api", decoded.service)
        assertEquals("GET /health", decoded.name)
        assertEquals("/health", decoded.resource)
        assertEquals("web", decoded.type)
        assertEquals(1_700_000_000_000L, decoded.start)
        assertEquals(42_000L, decoded.duration)
        assertEquals(1, decoded.error)
        assertEquals("GET", decoded.meta["http.method"])
        assertEquals(0.5, decoded.metrics["sampling.rate"])
        assertTrue(decoded.meta.isNotEmpty())
    }

    private fun buildProto(block: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply {
            block()
            flush()
        }
        return bytes.toByteArray()
    }
}
