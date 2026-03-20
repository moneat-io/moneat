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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpErrorExtractorTest {

    private class SpanTestBuilder {
        var traceId: String = "aabb"
        var spanId: String = "ccdd"
        var organizationId: Long = 1L
        var service: String = "my-svc"
        var env: String = "prod"
        var statusCode: Int = 0
        var statusMessage: String = ""
        var startNanos: Long = 1700000000000000000L
        var events: String = "[]"

        fun build(): OtlpSpanInsert = OtlpSpanInsert(
            traceIdHex = traceId,
            spanIdHex = spanId,
            parentIdHex = "",
            organizationId = organizationId,
            name = "test-op",
            service = service,
            resource = "test-op",
            kind = "SERVER",
            startNanos = startNanos,
            durationNanos = 50000000L,
            error = if (statusCode == 2) 1 else 0,
            statusCode = statusCode,
            statusMessage = statusMessage,
            meta = emptyMap(),
            resourceAttributes = emptyMap(),
            host = "web-01",
            env = env,
            version = "1.0",
            scopeName = "",
            scopeVersion = "",
            events = events,
            links = "[]",
        )
    }

    private fun buildSpan(configure: SpanTestBuilder.() -> Unit = {}): OtlpSpanInsert =
        SpanTestBuilder().apply(configure).build()

    // ──── EXCEPTION EVENTS ────

    @Test
    fun `extracts exception from span events`() {
        val eventsJson = """
        [{
          "name": "exception",
          "timeUnixNano": 1700000000050000000,
          "attributes": [
            {"key": "exception.type", "value": {"stringValue": "NullPointerException"}},
            {"key": "exception.message", "value": {"stringValue": "value was null"}},
            {"key": "exception.stacktrace", "value": {"stringValue": "at com.example.Main.run(Main.kt:42)"}}
          ]
        }]
        """.trimIndent()

        val span = buildSpan {
            statusCode = 2;
            events = eventsJson
        }
        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))

        assertEquals(1, exceptions.size)
        val ex = exceptions[0]
        assertEquals("NullPointerException", ex.exceptionType)
        assertEquals("value was null", ex.exceptionMessage)
        assertEquals("at com.example.Main.run(Main.kt:42)", ex.stackTrace)
        assertEquals("aabb", ex.traceIdHex)
        assertEquals("ccdd", ex.spanIdHex)
        assertEquals(1L, ex.organizationId)
        assertEquals("my-svc", ex.service)
        assertEquals("prod", ex.environment)
        assertEquals(1700000000050L, ex.timestampMs)
    }

    @Test
    fun `extracts multiple exception events from one span`() {
        val eventsJson = """
        [
          {
            "name": "exception",
            "timeUnixNano": 1000000000,
            "attributes": [
              {"key": "exception.type", "value": {"stringValue": "IOException"}}
            ]
          },
          {
            "name": "exception",
            "timeUnixNano": 2000000000,
            "attributes": [
              {"key": "exception.type", "value": {"stringValue": "TimeoutException"}}
            ]
          }
        ]
        """.trimIndent()

        val span = buildSpan {
            statusCode = 2;
            events = eventsJson
        }
        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))

        assertEquals(2, exceptions.size)
        assertEquals("IOException", exceptions[0].exceptionType)
        assertEquals("TimeoutException", exceptions[1].exceptionType)
    }

    // ──── ERROR STATUS WITHOUT EXCEPTION EVENT ────

    @Test
    fun `creates synthetic exception for error status without exception events`() {
        val span = buildSpan {
            statusCode = 2
            statusMessage = "Deadline exceeded"
            events = "[]"
        }

        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))

        assertEquals(1, exceptions.size)
        val ex = exceptions[0]
        assertEquals("SpanError", ex.exceptionType)
        assertEquals("Deadline exceeded", ex.exceptionMessage)
        assertEquals("", ex.stackTrace)
    }

    @Test
    fun `uses default message for error status without message`() {
        val span = buildSpan {
            statusCode = 2
            statusMessage = ""
            events = "[]"
        }

        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))

        assertEquals("Span completed with ERROR status", exceptions[0].exceptionMessage)
    }

    // ──── NON-ERROR SPANS WITH EXCEPTION EVENTS ────

    @Test
    fun `extracts exceptions even when status is not error if events contain exception`() {
        val eventsJson = """
        [{
          "name": "exception",
          "timeUnixNano": 1000000000,
          "attributes": [
            {"key": "exception.type", "value": {"stringValue": "RetryableError"}},
            {"key": "exception.message", "value": {"stringValue": "retried successfully"}}
          ]
        }]
        """.trimIndent()

        val span = buildSpan {
            statusCode = 0;
            events = eventsJson
        }
        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))

        assertEquals(1, exceptions.size)
        assertEquals("RetryableError", exceptions[0].exceptionType)
    }

    // ──── NO ERRORS ────

    @Test
    fun `returns empty list for non-error spans without exception events`() {
        val span = buildSpan {
            statusCode = 0;
            events = "[]"
        }

        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))
        assertTrue(exceptions.isEmpty())
    }

    @Test
    fun `returns empty list for empty span list`() {
        val exceptions = OtlpErrorExtractor.extractExceptions(emptyList())
        assertTrue(exceptions.isEmpty())
    }

    // ──── IGNORES NON-EXCEPTION EVENTS ────

    @Test
    fun `ignores non-exception events`() {
        val eventsJson = """
        [
          {"name": "log", "attributes": [{"key": "msg", "value": {"stringValue": "debug info"}}]},
          {"name": "exception", "attributes": [{"key": "exception.type", "value": {"stringValue": "RealError"}}]}
        ]
        """.trimIndent()

        val span = buildSpan {
            statusCode = 2;
            events = eventsJson
        }
        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))

        assertEquals(1, exceptions.size)
        assertEquals("RealError", exceptions[0].exceptionType)
    }

    // ──── MISSING EXCEPTION ATTRIBUTES ────

    @Test
    fun `defaults exception type to UnknownError when missing`() {
        val eventsJson = """
        [{
          "name": "exception",
          "attributes": [
            {"key": "exception.message", "value": {"stringValue": "something broke"}}
          ]
        }]
        """.trimIndent()

        val span = buildSpan {
            statusCode = 2;
            events = eventsJson
        }
        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))

        assertEquals("UnknownError", exceptions[0].exceptionType)
        assertEquals("something broke", exceptions[0].exceptionMessage)
    }

    // ──── MALFORMED EVENTS JSON ────

    @Test
    fun `handles malformed events JSON gracefully`() {
        val span = buildSpan {
            statusCode = 2;
            events = "not valid json"
        }

        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span))
        assertEquals(1, exceptions.size)
        assertEquals("SpanError", exceptions[0].exceptionType)
    }

    // ──── MULTIPLE SPANS ────

    @Test
    fun `extracts exceptions from multiple spans`() {
        val span1 = buildSpan {
            traceId = "trace1"
            spanId = "span1"
            statusCode = 2
            statusMessage = "error 1"
            events = "[]"
        }
        val span2 = buildSpan {
            traceId = "trace2"
            spanId = "span2"
            statusCode = 0
            events = "[]"
        }
        val span3Events = """
        [{"name": "exception", "attributes": [
          {"key": "exception.type", "value": {"stringValue": "FatalError"}}
        ]}]
        """.trimIndent()
        val span3 = buildSpan {
            traceId = "trace3"
            spanId = "span3"
            statusCode = 2
            events = span3Events
        }

        val exceptions = OtlpErrorExtractor.extractExceptions(listOf(span1, span2, span3))

        assertEquals(2, exceptions.size)
        assertEquals("SpanError", exceptions[0].exceptionType)
        assertEquals("FatalError", exceptions[1].exceptionType)
    }
}
