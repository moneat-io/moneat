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
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtlpTraceServiceTest {

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "test_db"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    private val service by lazy { OtlpTraceService() }

    // ──── BASIC PARSING ────

    @Test
    fun `parseOtlpTracesJson parses single span`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {
              "attributes": [
                {"key": "service.name", "value": {"stringValue": "my-service"}},
                {"key": "deployment.environment", "value": {"stringValue": "prod"}},
                {"key": "host.name", "value": {"stringValue": "web-01"}},
                {"key": "service.version", "value": {"stringValue": "1.0.0"}}
              ]
            },
            "scopeSpans": [{
              "scope": {"name": "io.otel.sdk", "version": "1.30.0"},
              "spans": [{
                "traceId": "0af7651916cd43dd8448eb211c80319c",
                "spanId": "b7ad6b7169203331",
                "parentSpanId": "",
                "name": "GET /api/users",
                "kind": 2,
                "startTimeUnixNano": 1700000000000000000,
                "endTimeUnixNano":   1700000000050000000,
                "status": {"code": 1, "message": "OK"},
                "attributes": [
                  {"key": "http.method", "value": {"stringValue": "GET"}},
                  {"key": "http.status_code", "value": {"intValue": "200"}}
                ],
                "events": [],
                "links": []
              }]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!

        assertEquals(1, spans.size)
        val s = spans[0]
        assertEquals("0af7651916cd43dd8448eb211c80319c", s.traceIdHex)
        assertEquals("b7ad6b7169203331", s.spanIdHex)
        assertEquals("", s.parentIdHex)
        assertEquals("GET /api/users", s.name)
        assertEquals("my-service", s.service)
        assertEquals("SERVER", s.kind)
        assertEquals(1700000000000000000L, s.startNanos)
        assertEquals(50000000L, s.durationNanos)
        assertEquals(1, s.statusCode)
        assertEquals("OK", s.statusMessage)
        assertEquals(0, s.error)
        assertEquals("GET", s.meta["http.method"])
        assertEquals("200", s.meta["http.status_code"])
        assertEquals("prod", s.env)
        assertEquals("web-01", s.host)
        assertEquals("1.0.0", s.version)
        assertEquals("io.otel.sdk", s.scopeName)
        assertEquals("1.30.0", s.scopeVersion)
        assertEquals("my-service", s.resourceAttributes["service.name"])
    }

    // ──── SPAN KIND MAPPING ────

    @Test
    fun `maps all span kinds correctly`() {
        val template = { kind: Int ->
            """
        {
          "resourceSpans": [{
            "resource": {},
            "scopeSpans": [{
              "spans": [{
                "traceId": "aa", "spanId": "bb", "name": "test",
                "kind": $kind,
                "startTimeUnixNano": 0, "endTimeUnixNano": 0,
                "status": {}
              }]
            }]
          }]
        }
            """.trimIndent()
        }

        val expected = mapOf(
            0 to "",
            1 to "INTERNAL",
            2 to "SERVER",
            3 to "CLIENT",
            4 to "PRODUCER",
            5 to "CONSUMER"
        )

        for ((kindInt, kindStr) in expected) {
            val spans = service.parseOtlpTracesJson(template(kindInt))!!
            assertEquals(kindStr, spans[0].kind, "Kind $kindInt should map to $kindStr")
        }
    }

    // ──── ERROR STATUS ────

    @Test
    fun `marks error flag for status code 2`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {},
            "scopeSpans": [{
              "spans": [{
                "traceId": "aa", "spanId": "bb", "name": "fail-op",
                "startTimeUnixNano": 0, "endTimeUnixNano": 0,
                "status": {"code": 2, "message": "Internal error"}
              }]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!

        assertEquals(1, spans[0].error)
        assertEquals(2, spans[0].statusCode)
        assertEquals("Internal error", spans[0].statusMessage)
    }

    @Test
    fun `non-error status code does not set error flag`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {},
            "scopeSpans": [{
              "spans": [{
                "traceId": "aa", "spanId": "bb", "name": "ok-op",
                "startTimeUnixNano": 0, "endTimeUnixNano": 0,
                "status": {"code": 0}
              }]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!
        assertEquals(0, spans[0].error)
    }

    // ──── DURATION CLAMPING ────

    @Test
    fun `clamps negative duration to zero when end is before start`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {},
            "scopeSpans": [{
              "spans": [{
                "traceId": "aa", "spanId": "bb", "name": "backwards-time",
                "startTimeUnixNano": 2000000000,
                "endTimeUnixNano": 1000000000,
                "status": {}
              }]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!

        assertEquals(0L, spans[0].durationNanos)
    }

    @Test
    fun `calculates positive duration normally`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {},
            "scopeSpans": [{
              "spans": [{
                "traceId": "aa", "spanId": "bb", "name": "normal",
                "startTimeUnixNano": 1000000000,
                "endTimeUnixNano": 1050000000,
                "status": {}
              }]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!

        assertEquals(50000000L, spans[0].durationNanos)
    }

    // ──── MULTIPLE RESOURCE SPANS / SCOPE SPANS ────

    @Test
    fun `parses multiple resource spans and scope spans`() {
        val payload = """
        {
          "resourceSpans": [
            {
              "resource": {
                "attributes": [{"key": "service.name", "value": {"stringValue": "svc-a"}}]
              },
              "scopeSpans": [{
                "spans": [
                  {"traceId": "aa", "spanId": "11", "name": "span-a1", "startTimeUnixNano": 0, "endTimeUnixNano": 0, "status": {}},
                  {"traceId": "aa", "spanId": "12", "name": "span-a2", "startTimeUnixNano": 0, "endTimeUnixNano": 0, "status": {}}
                ]
              }]
            },
            {
              "resource": {
                "attributes": [{"key": "service.name", "value": {"stringValue": "svc-b"}}]
              },
              "scopeSpans": [{
                "spans": [
                  {"traceId": "bb", "spanId": "21", "name": "span-b1", "startTimeUnixNano": 0, "endTimeUnixNano": 0, "status": {}}
                ]
              }]
            }
          ]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!

        assertEquals(3, spans.size)
        assertEquals("svc-a", spans[0].service)
        assertEquals("svc-a", spans[1].service)
        assertEquals("svc-b", spans[2].service)
    }

    // ──── LEGACY FIELD NAME ────

    @Test
    fun `supports instrumentationLibrarySpans as legacy field`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {},
            "instrumentationLibrarySpans": [{
              "spans": [{
                "traceId": "cc", "spanId": "dd", "name": "legacy-span",
                "startTimeUnixNano": 0, "endTimeUnixNano": 0, "status": {}
              }]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!
        assertEquals(1, spans.size)
        assertEquals("legacy-span", spans[0].name)
    }

    // ──── EVENTS AND LINKS SERIALIZATION ────

    @Test
    fun `preserves events and links as JSON strings`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {},
            "scopeSpans": [{
              "spans": [{
                "traceId": "aa", "spanId": "bb", "name": "with-events",
                "startTimeUnixNano": 0, "endTimeUnixNano": 0, "status": {},
                "events": [{"name": "exception", "timeUnixNano": 100}],
                "links": [{"traceId": "linked-trace", "spanId": "linked-span"}]
              }]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!

        assertTrue(spans[0].events.contains("exception"))
        assertTrue(spans[0].links.contains("linked-trace"))
    }

    // ──── EMPTY / INVALID PAYLOADS ────

    @Test
    fun `returns empty list for valid payload with empty resourceSpans`() {
        val payload = """{"resourceSpans": []}"""
        val result = service.parseOtlpTracesJson(payload)
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns null for missing resourceSpans`() {
        val payload = """{"otherField": "value"}"""
        assertNull(service.parseOtlpTracesJson(payload))
    }

    @Test
    fun `returns null for invalid JSON`() {
        assertNull(service.parseOtlpTracesJson("not json"))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(service.parseOtlpTracesJson(""))
    }

    // ──── DEFAULTS FOR MISSING FIELDS ────

    @Test
    fun `handles span with minimal fields`() {
        val payload = """
        {
          "resourceSpans": [{
            "resource": {},
            "scopeSpans": [{
              "spans": [{"traceId": "aa", "spanId": "bb"}]
            }]
          }]
        }
        """.trimIndent()

        val spans = service.parseOtlpTracesJson(payload)!!

        assertEquals(1, spans.size)
        val s = spans[0]
        assertEquals("aa", s.traceIdHex)
        assertEquals("bb", s.spanIdHex)
        assertEquals("", s.parentIdHex)
        assertEquals("", s.name)
        assertEquals("", s.kind)
        assertEquals(0L, s.startNanos)
        assertEquals(0L, s.durationNanos)
        assertEquals(0, s.statusCode)
        assertEquals("", s.statusMessage)
        assertEquals(0, s.error)
        assertTrue(s.meta.isEmpty())
        assertEquals("[]", s.events)
        assertEquals("[]", s.links)
    }

    // ──── BATCH DECODE ROUNDTRIP ────

    @Test
    fun `decodeBatch roundtrips correctly`() {
        val batch = QueuedOtlpTraceBatch(
            organizationId = 42L,
            spans = listOf(
                OtlpSpanInsert(
                    traceIdHex = "aabb",
                    spanIdHex = "ccdd",
                    parentIdHex = "",
                    organizationId = 42L,
                    name = "test-span",
                    service = "my-svc",
                    resource = "test-span",
                    kind = "SERVER",
                    startNanos = 1000000000L,
                    durationNanos = 500000L,
                    error = 0,
                    statusCode = 1,
                    statusMessage = "OK",
                    meta = mapOf("key" to "value"),
                    resourceAttributes = mapOf("service.name" to "my-svc"),
                    host = "web-01",
                    env = "prod",
                    version = "1.0",
                    scopeName = "otel-sdk",
                    scopeVersion = "1.30.0",
                    events = "[]",
                    links = "[]",
                )
            )
        )

        val encoded = Json.encodeToString(batch)
        val decoded = service.decodeBatch(encoded)

        assertEquals(batch.organizationId, decoded.organizationId)
        assertEquals(batch.spans.size, decoded.spans.size)
        assertEquals("test-span", decoded.spans[0].name)
        assertEquals("my-svc", decoded.spans[0].service)
        assertEquals("SERVER", decoded.spans[0].kind)
        assertEquals(mapOf("key" to "value"), decoded.spans[0].meta)
    }
}
