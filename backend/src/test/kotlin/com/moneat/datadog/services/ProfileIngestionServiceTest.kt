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

import com.moneat.datadog.models.DdProfileEndpoint
import com.moneat.datadog.models.DdProfileEvent
import com.moneat.utils.ClickHouseSqlUtils
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileIngestionServiceTest {

    // Access private parseDdTags via reflection for testing
    private val parseDdTagsMethod: Method =
        ProfileIngestionService::class.java
            .getDeclaredMethod("parseDdTags", String::class.java)
            .also { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun parseDdTags(tags: String): Map<String, String> =
        parseDdTagsMethod.invoke(
            ProfileIngestionService,
            tags
        ) as Map<String, String>

    private val extractProfileTagsMethod: Method =
        ProfileIngestionService::class.java
            .getDeclaredMethod("extractProfileTags", DdProfileEvent::class.java)
            .also { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun extractProfileTags(event: DdProfileEvent): Map<String, String> =
        extractProfileTagsMethod.invoke(
            ProfileIngestionService,
            event
        ) as Map<String, String>

    private val firstNonBlankTagMethod: Method =
        ProfileIngestionService::class.java
            .getDeclaredMethod(
                "firstNonBlankTag",
                Map::class.java,
                Array<String>::class.java
            )
            .also { it.isAccessible = true }

    private fun firstNonBlankTag(
        tags: Map<String, String>,
        vararg keys: String,
    ): String = firstNonBlankTagMethod.invoke(
        ProfileIngestionService,
        tags,
        keys
    ) as String

    // ──── DD TAG PARSING TESTS ────

    @Test
    fun `parseDdTags parses comma-separated key-value pairs`() {
        val tags = "service:my-app,env:production,version:1.0.0"
        val result = parseDdTags(tags)

        assertEquals("my-app", result["service"])
        assertEquals("production", result["env"])
        assertEquals("1.0.0", result["version"])
        assertEquals(3, result.size)
    }

    @Test
    fun `parseDdTags handles values with colons`() {
        val tags = "host:ip-10-0-1-42,url:https://example.com:8080/api"
        val result = parseDdTags(tags)

        assertEquals("ip-10-0-1-42", result["host"])
        assertEquals(
            "https://example.com:8080/api",
            result["url"],
            "Values containing colons should preserve everything after first colon"
        )
    }

    @Test
    fun `parseDdTags handles empty string`() {
        val result = parseDdTags("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseDdTags handles blank string`() {
        val result = parseDdTags("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseDdTags handles whitespace around tags`() {
        val tags = " service:my-app , env:staging "
        val result = parseDdTags(tags)

        assertEquals("my-app", result["service"])
        assertEquals("staging", result["env"])
    }

    @Test
    fun `parseDdTags skips malformed entries without colon`() {
        val tags = "service:my-app,badentry,env:dev"
        val result = parseDdTags(tags)

        assertEquals(2, result.size)
        assertEquals("my-app", result["service"])
        assertEquals("dev", result["env"])
    }

    @Test
    fun `extractProfileTags merges event tags and tags_profiler`() {
        val event = DdProfileEvent(
            tags = "service:api,env:prod",
            tagsProfiler = "service.name:api-v2,runtime:java17,env:   "
        )

        val result = extractProfileTags(event)

        assertEquals("api", result["service"])
        assertEquals("api-v2", result["service.name"])
        assertEquals("java17", result["runtime"])
        assertEquals("prod", result["env"])
    }

    @Test
    fun `firstNonBlankTag resolves aliases and skips blank values`() {
        val tags = mapOf(
            "service" to " ",
            "service.name" to "billing-api",
            "service_name" to "billing-fallback"
        )

        val service = firstNonBlankTag(
            tags,
            "service",
            "service.name",
            "service_name"
        )

        assertEquals("billing-api", service)
    }

    // ──── PROFILE EVENT MODEL TESTS ────

    @Test
    fun `DdProfileEvent has correct defaults`() {
        val event = DdProfileEvent()

        assertEquals("4", event.version)
        assertEquals("", event.family)
        assertEquals("", event.start)
        assertEquals("", event.end)
        assertEquals("", event.tags)
        assertEquals("", event.tagsProfiler)
        assertEquals(null, event.endpoint)
    }

    @Test
    fun `DdProfileEvent with endpoint`() {
        val event = DdProfileEvent(
            start = "2026-01-15T10:00:00Z",
            end = "2026-01-15T10:01:00Z",
            tags = "service:backend,env:prod",
            endpoint = DdProfileEndpoint(
                localRootSpanId = 12345L,
                traceId = 67890L
            )
        )

        assertEquals("2026-01-15T10:00:00Z", event.start)
        assertEquals("2026-01-15T10:01:00Z", event.end)
        assertEquals(12345L, event.endpoint?.localRootSpanId)
        assertEquals(67890L, event.endpoint?.traceId)
    }

    // ──── SQL ESCAPING TESTS ────

    @Test
    fun `escapeSql escapes single quotes`() {
        assertEquals(
            "O\\'Reilly",
            ClickHouseSqlUtils.escapeSql("O'Reilly"),
            "Single quotes should be escaped"
        )
    }

    @Test
    fun `escapeSql escapes backslashes`() {
        assertEquals(
            "C:\\\\Users",
            ClickHouseSqlUtils.escapeSql("C:\\Users"),
            "Backslashes should be escaped"
        )
    }

    @Test
    fun `escapeSql handles normal strings unchanged`() {
        assertEquals("hello world", ClickHouseSqlUtils.escapeSql("hello world"))
    }

    @Test
    fun `escapeSql handles empty string`() {
        assertEquals("", ClickHouseSqlUtils.escapeSql(""))
    }

    @Test
    fun `escapeSql handles combined special chars`() {
        assertEquals(
            "it\\'s a \\\\path",
            ClickHouseSqlUtils.escapeSql("it's a \\path")
        )
    }

    // ──── MAP TO SQL TESTS ────

    private val mapToSqlMapMethod: Method =
        ProfileIngestionService::class.java
            .getDeclaredMethod("mapToSqlMap", Map::class.java)
            .also { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun mapToSqlMap(map: Map<String, String>): String =
        mapToSqlMapMethod.invoke(ProfileIngestionService, map) as String

    @Test
    fun `mapToSqlMap produces empty map expression for empty map`() {
        assertEquals("map()", mapToSqlMap(emptyMap()))
    }

    @Test
    fun `mapToSqlMap produces correct SQL for single entry`() {
        val result = mapToSqlMap(mapOf("key" to "value"))
        assertEquals("map('key', 'value')", result)
    }

    @Test
    fun `mapToSqlMap escapes special chars in keys and values`() {
        val result = mapToSqlMap(mapOf("it's" to "O'Neil"))
        assertTrue(
            result.contains("\\'"),
            "Should escape quotes in map entries"
        )
    }
}
