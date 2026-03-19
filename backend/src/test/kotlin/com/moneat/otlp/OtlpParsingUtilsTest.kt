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

package com.moneat.otlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtlpParsingUtilsTest {

    // ──── attributesToMap ────

    @Nested
    inner class AttributesToMap {

        @Test
        fun `parses standard key-value attributes`() {
            val attrs = buildJsonArray {
                add(
                    buildJsonObject {
                        put("key", "service.name")
                        put("value", buildJsonObject { put("stringValue", "my-app") })
                    }
                )
                add(
                    buildJsonObject {
                        put("key", "http.status_code")
                        put("value", buildJsonObject { put("intValue", "200") })
                    }
                )
            }

            val result = OtlpParsingUtils.attributesToMap(attrs)

            assertEquals("my-app", result["service.name"])
            assertEquals("200", result["http.status_code"])
        }

        @Test
        fun `handles doubleValue and boolValue`() {
            val attrs = buildJsonArray {
                add(
                    buildJsonObject {
                        put("key", "latency")
                        put("value", buildJsonObject { put("doubleValue", "3.14") })
                    }
                )
                add(
                    buildJsonObject {
                        put("key", "enabled")
                        put("value", buildJsonObject { put("boolValue", "true") })
                    }
                )
            }

            val result = OtlpParsingUtils.attributesToMap(attrs)

            assertEquals("3.14", result["latency"])
            assertEquals("true", result["enabled"])
        }

        @Test
        fun `handles bytesValue`() {
            val attrs = buildJsonArray {
                add(
                    buildJsonObject {
                        put("key", "data")
                        put("value", buildJsonObject { put("bytesValue", "AQID") })
                    }
                )
            }

            val result = OtlpParsingUtils.attributesToMap(attrs)
            assertEquals("AQID", result["data"])
        }

        @Test
        fun `handles arrayValue and kvlistValue as JSON strings`() {
            val attrs = buildJsonArray {
                add(
                    buildJsonObject {
                        put("key", "tags")
                        put(
                            "value",
                            buildJsonObject {
                                put(
                                    "arrayValue",
                                    buildJsonObject {
                                        put(
                                            "values",
                                            buildJsonArray {
                                                add(buildJsonObject { put("stringValue", "a") })
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
                add(
                    buildJsonObject {
                        put("key", "meta")
                        put(
                            "value",
                            buildJsonObject {
                                put(
                                    "kvlistValue",
                                    buildJsonObject {
                                        put("values", buildJsonArray {})
                                    }
                                )
                            }
                        )
                    }
                )
            }

            val result = OtlpParsingUtils.attributesToMap(attrs)

            assertTrue(result["tags"]!!.contains("values"))
            assertTrue(result["meta"]!!.contains("values"))
        }

        @Test
        fun `returns empty map for null input`() {
            val result = OtlpParsingUtils.attributesToMap(null)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `returns empty map for non-array input`() {
            val result = OtlpParsingUtils.attributesToMap(JsonPrimitive("not-an-array"))
            assertTrue(result.isEmpty())
        }

        @Test
        fun `skips entries without key or value`() {
            val attrs = buildJsonArray {
                add(
                    buildJsonObject {
                        put("value", buildJsonObject { put("stringValue", "no-key") })
                    }
                )
                add(
                    buildJsonObject {
                        put("key", "has-key")
                        put("value", buildJsonObject { put("stringValue", "good") })
                    }
                )
            }

            val result = OtlpParsingUtils.attributesToMap(attrs)
            assertEquals(1, result.size)
            assertEquals("good", result["has-key"])
        }
    }

    // ──── extractAnyValue ────

    @Nested
    inner class ExtractAnyValue {

        @Test
        fun `extracts stringValue`() {
            val value = buildJsonObject { put("stringValue", "hello") }
            assertEquals("hello", OtlpParsingUtils.extractAnyValue(value))
        }

        @Test
        fun `extracts intValue`() {
            val value = buildJsonObject { put("intValue", "42") }
            assertEquals("42", OtlpParsingUtils.extractAnyValue(value))
        }

        @Test
        fun `extracts doubleValue`() {
            val value = buildJsonObject { put("doubleValue", "3.14") }
            assertEquals("3.14", OtlpParsingUtils.extractAnyValue(value))
        }

        @Test
        fun `extracts boolValue`() {
            val value = buildJsonObject { put("boolValue", "true") }
            assertEquals("true", OtlpParsingUtils.extractAnyValue(value))
        }

        @Test
        fun `returns null for empty object`() {
            val value = buildJsonObject {}
            assertNull(OtlpParsingUtils.extractAnyValue(value))
        }

        @Test
        fun `returns null for null input`() {
            assertNull(OtlpParsingUtils.extractAnyValue(null))
        }

        @Test
        fun `falls back to primitive contentOrNull for non-object`() {
            val value = JsonPrimitive("raw-string")
            assertEquals("raw-string", OtlpParsingUtils.extractAnyValue(value))
        }
    }

    // ──── extractResourceContext ────

    @Nested
    inner class ExtractResourceContext {

        @Test
        fun `extracts all standard resource attributes`() {
            val resource = buildJsonObject {
                put(
                    "attributes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("key", "service.name")
                                put("value", buildJsonObject { put("stringValue", "payment-svc") })
                            }
                        )
                        add(
                            buildJsonObject {
                                put("key", "deployment.environment")
                                put("value", buildJsonObject { put("stringValue", "production") })
                            }
                        )
                        add(
                            buildJsonObject {
                                put("key", "host.name")
                                put("value", buildJsonObject { put("stringValue", "web-01") })
                            }
                        )
                        add(
                            buildJsonObject {
                                put("key", "service.version")
                                put("value", buildJsonObject { put("stringValue", "2.1.0") })
                            }
                        )
                    }
                )
            }

            val ctx = OtlpParsingUtils.extractResourceContext(resource)

            assertEquals("payment-svc", ctx.serviceName)
            assertEquals("production", ctx.environment)
            assertEquals("web-01", ctx.hostName)
            assertEquals("2.1.0", ctx.serviceVersion)
            assertEquals(4, ctx.attributes.size)
        }

        @Test
        fun `falls back to service_environment for environment`() {
            val resource = buildJsonObject {
                put(
                    "attributes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("key", "service.environment")
                                put("value", buildJsonObject { put("stringValue", "staging") })
                            }
                        )
                    }
                )
            }

            val ctx = OtlpParsingUtils.extractResourceContext(resource)
            assertEquals("staging", ctx.environment)
        }

        @Test
        fun `returns defaults for null resource`() {
            val ctx = OtlpParsingUtils.extractResourceContext(null)

            assertEquals("", ctx.serviceName)
            assertEquals("", ctx.environment)
            assertEquals("", ctx.hostName)
            assertEquals("", ctx.serviceVersion)
            assertTrue(ctx.attributes.isEmpty())
        }

        @Test
        fun `returns defaults for resource without attributes`() {
            val resource = buildJsonObject {}

            val ctx = OtlpParsingUtils.extractResourceContext(resource)
            assertEquals("", ctx.serviceName)
            assertTrue(ctx.attributes.isEmpty())
        }
    }

    // ──── nanoToEpochMs ────

    @Nested
    inner class NanoToEpochMs {

        @Test
        fun `converts nanosecond string to milliseconds`() {
            assertEquals(1700000000000L, OtlpParsingUtils.nanoToEpochMs("1700000000000000000"))
        }

        @Test
        fun `returns null for null string`() {
            assertNull(OtlpParsingUtils.nanoToEpochMs(null as String?))
        }

        @Test
        fun `returns null for non-numeric string`() {
            assertNull(OtlpParsingUtils.nanoToEpochMs("not-a-number"))
        }

        @Test
        fun `converts nanosecond Long to milliseconds`() {
            assertEquals(1700000000000L, OtlpParsingUtils.nanoToEpochMs(1700000000000000000L))
        }

        @Test
        fun `returns null for null Long`() {
            assertNull(OtlpParsingUtils.nanoToEpochMs(null as Long?))
        }

        @Test
        fun `handles zero`() {
            assertEquals(0L, OtlpParsingUtils.nanoToEpochMs("0"))
            assertEquals(0L, OtlpParsingUtils.nanoToEpochMs(0L))
        }
    }

    // ──── extractTimestampNanos ────

    @Nested
    inner class ExtractTimestampNanos {

        @Test
        fun `extracts first matching field`() {
            val record = Json.parseToJsonElement(
                """
                {"startTimeUnixNano": 1000000000, "endTimeUnixNano": 2000000000}
                """.trimIndent()
            ).jsonObject

            assertEquals(
                1000000000L,
                OtlpParsingUtils.extractTimestampNanos(record, "startTimeUnixNano", "endTimeUnixNano")
            )
        }

        @Test
        fun `falls back to second field if first is missing`() {
            val record = Json.parseToJsonElement(
                """
                {"endTimeUnixNano": 2000000000}
                """.trimIndent()
            ).jsonObject

            assertEquals(
                2000000000L,
                OtlpParsingUtils.extractTimestampNanos(record, "startTimeUnixNano", "endTimeUnixNano")
            )
        }

        @Test
        fun `returns null when no fields match`() {
            val record = Json.parseToJsonElement(
                """
                {"other": "value"}
                """.trimIndent()
            ).jsonObject

            assertNull(OtlpParsingUtils.extractTimestampNanos(record, "startTimeUnixNano"))
        }
    }

    // ──── extractScopeName / extractScopeVersion ────

    @Nested
    inner class ScopeExtraction {

        @Test
        fun `extracts scope name and version`() {
            val scopeElement = Json.parseToJsonElement(
                """
                {"scope": {"name": "io.opentelemetry.sdk", "version": "1.30.0"}}
                """.trimIndent()
            ).jsonObject

            assertEquals("io.opentelemetry.sdk", OtlpParsingUtils.extractScopeName(scopeElement))
            assertEquals("1.30.0", OtlpParsingUtils.extractScopeVersion(scopeElement))
        }

        @Test
        fun `returns empty string when scope is missing`() {
            val scopeElement = Json.parseToJsonElement("{}").jsonObject

            assertEquals("", OtlpParsingUtils.extractScopeName(scopeElement))
            assertEquals("", OtlpParsingUtils.extractScopeVersion(scopeElement))
        }

        @Test
        fun `returns empty string when scope fields are missing`() {
            val scopeElement = Json.parseToJsonElement(
                """
                {"scope": {}}
                """.trimIndent()
            ).jsonObject

            assertEquals("", OtlpParsingUtils.extractScopeName(scopeElement))
            assertEquals("", OtlpParsingUtils.extractScopeVersion(scopeElement))
        }
    }

    // ──── safeJsonArray ────

    @Nested
    inner class SafeJsonArray {

        @Test
        fun `returns the array when element is a JsonArray`() {
            val arr = buildJsonArray { add(JsonPrimitive("item")) }
            assertEquals(1, OtlpParsingUtils.safeJsonArray(arr).size)
        }

        @Test
        fun `returns empty array for null`() {
            assertEquals(0, OtlpParsingUtils.safeJsonArray(null).size)
        }
    }
}
