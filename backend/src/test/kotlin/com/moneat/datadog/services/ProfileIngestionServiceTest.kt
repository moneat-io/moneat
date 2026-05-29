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
import com.moneat.datadog.models.DdProfileEndpoint
import com.moneat.datadog.models.DdProfileEvent
import com.moneat.utils.ClickHouseSqlUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    // ──── Dashboard query tests ────

    @Test
    fun `listProfiles applies filters and maps rows`() = runBlocking {
        val queries = mutableListOf<String>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                val sql = firstArg<String>()
                queries.add(sql)
                if (sql.contains("SELECT count()")) {
                    "1"
                } else {
                    profileJsonRow()
                }
            }

            val response = ProfileIngestionService.listProfiles(
                organizationId = 42,
                query = DdProfileListQuery(
                    service = "api",
                    profileType = "cpu",
                    source = "datadog",
                    env = "prod",
                    host = "host-a",
                    version = "v1",
                    fromMs = 1000,
                    toMs = 2000,
                    limit = 5,
                    offset = 2,
                ),
            )

            assertEquals(1, response.totalCount)
            assertEquals("profile-1", response.profiles.single().profileId)
            assertEquals("api", response.profiles.single().service)
            assertTrue(queries.any { it.contains("service = 'api'") })
            assertTrue(queries.any { it.contains("start_time >= fromUnixTimestamp64Milli(1000)") })
            assertTrue(queries.any { it.contains("LIMIT 5 OFFSET 2") })
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `listProfiles and profile lookups handle empty ClickHouse results`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                val sql = firstArg<String>()
                if (sql.contains("SELECT count()")) {
                    "not-a-number"
                } else {
                    "\n"
                }
            }

            val response = ProfileIngestionService.listProfiles(
                organizationId = 42,
                query = DdProfileListQuery(limit = 999),
            )
            val profile = ProfileIngestionService.getProfile(
                organizationId = 42,
                profileId = "missing-profile",
            )
            val storageKey = ProfileIngestionService.getProfileStorageKey(
                organizationId = 42,
                profileId = "missing-profile",
            )
            val meta = ProfileIngestionService.getProfileMeta(
                organizationId = 42,
                profileId = "missing-profile",
            )

            assertEquals(0, response.totalCount)
            assertTrue(response.profiles.isEmpty())
            assertNull(profile)
            assertNull(storageKey)
            assertNull(meta)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `listProfiles maps optional defaults and service tag fallback`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                val sql = firstArg<String>()
                if (sql.contains("SELECT count()")) {
                    "1"
                } else {
                    compactJson(
                        """{"profile_id":"profile-2","service":"","profile_type":"cpu",""",
                        """"start_time":"2026-01-01 00:00:00","end_time":"2026-01-01 00:00:01",""",
                        """"duration_ns":42,"size_bytes":7,"tags":{"service.name":"fallback-api"}}""",
                    )
                }
            }

            val profile = ProfileIngestionService.listProfiles(
                organizationId = 42,
                query = DdProfileListQuery(),
            ).profiles.single()

            assertEquals("fallback-api", profile.service)
            assertEquals("", profile.host)
            assertEquals("", profile.env)
            assertEquals("", profile.version)
            assertEquals("", profile.runtime)
            assertEquals("", profile.language)
            assertEquals("datadog", profile.source)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `listServices maps rollups type counts and sparkline series`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                val sql = firstArg<String>()
                when {
                    sql.contains("profile_type AS profileType") ->
                        """{"service":"api","profileType":"cpu","count":3}"""
                    sql.contains("GROUP BY service, ts") ->
                        """{"service":"api","ts":1000,"count":2}"""
                    else -> profileServiceRollupJson()
                }
            }

            val response = ProfileIngestionService.listServices(
                organizationId = 42,
                fromMs = 1000,
                toMs = 2000,
            )

            val service = response.services.single()
            assertEquals("api", service.service)
            assertEquals(listOf("java"), service.languages)
            assertEquals("cpu", service.types.single().profileType)
            assertEquals(3, service.profileCount)
            assertEquals(2, service.series.single().count)
            assertEquals(3, response.totalProfiles)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `listServices maps sparse rows and default lookback sparkline`() = runBlocking {
        val queries = mutableListOf<String>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                val sql = firstArg<String>()
                queries.add(sql)
                when {
                    sql.contains("profile_type AS profileType") ->
                        """{"service":"","count":1}"""
                    sql.contains("GROUP BY service, ts") ->
                        """{"service":"","count":2}"""
                    else ->
                        """{"service":"","languages":["","kotlin"]}"""
                }
            }

            val response = ProfileIngestionService.listServices(
                organizationId = 42,
                fromMs = null,
                toMs = null,
            )

            val service = response.services.single()
            assertEquals("", service.service)
            assertEquals(listOf("kotlin"), service.languages)
            assertTrue(service.runtimes.isEmpty())
            assertTrue(service.environments.isEmpty())
            assertEquals("", service.types.single().profileType)
            assertEquals(1, service.types.single().count)
            assertEquals(0, service.hostCount)
            assertEquals(0, service.profileCount)
            assertEquals(0, service.totalSizeBytes)
            assertEquals("", service.firstSeen)
            assertEquals("", service.lastSeen)
            assertEquals(0, service.avgDurationNs)
            assertEquals(0, service.series.single().ts)
            assertEquals(2, service.series.single().count)
            assertTrue(queries.any { it.contains("now() - INTERVAL") })
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `timeseries maps buckets and applies query filters`() = runBlocking {
        val queries = mutableListOf<String>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                firstArg<String>().also { queries.add(it) }
                """{"ts":60000,"count":4,"sizeBytes":4096}"""
            }

            val response = ProfileIngestionService.timeseries(
                ProfileTimeseriesQuery(
                    organizationId = 42,
                    filters = ProfileQueryFilters(
                        service = "api",
                        profileType = "cpu",
                        env = "prod",
                        host = "host-a",
                    ),
                    window = ProfileTimeWindow(fromMs = 0, toMs = 120_000),
                    buckets = 2,
                ),
            )

            assertEquals(60, response.bucketSeconds)
            assertEquals(4, response.points.single().count)
            assertEquals(4096, response.points.single().sizeBytes)
            assertTrue(queries.single().contains("profile_type = 'cpu'"))
            assertTrue(queries.single().contains("host = 'host-a'"))
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `timeseries maps sparse rows and clamps invalid bucket count`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } returns "{}"

            val response = ProfileIngestionService.timeseries(
                ProfileTimeseriesQuery(
                    organizationId = 42,
                    filters = ProfileQueryFilters(),
                    window = ProfileTimeWindow(fromMs = 0, toMs = 500),
                    buckets = 0,
                ),
            )

            val point = response.points.single()
            assertEquals(60, response.bucketSeconds)
            assertEquals(0, point.ts)
            assertEquals(0, point.count)
            assertEquals(0, point.sizeBytes)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `selectProfilesForMerge caps candidates and defaults missing source`() = runBlocking {
        val queries = mutableListOf<String>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                firstArg<String>().also { queries.add(it) }
                """
                    {"profile_id":"profile-1","storage_key":"key-1","profileType":"jfr","total":7}
                    {"profile_id":"profile-2","storage_key":"","profileType":"cpu","source":"sentry","total":7}
                """.trimIndent()
            }

            val selection = ProfileIngestionService.selectProfilesForMerge(
                ProfileMergeSelectionQuery(
                    organizationId = 42,
                    filters = ProfileQueryFilters(service = "api", version = "v1"),
                    window = ProfileTimeWindow(fromMs = 1000, toMs = 2000),
                    maxProfiles = 500,
                ),
            )

            assertEquals(7, selection.totalInWindow)
            assertEquals("profile-1", selection.candidates.single().profileId)
            assertEquals("datadog", selection.candidates.single().source)
            assertTrue(queries.single().contains("LIMIT 50"))
            assertTrue(queries.single().contains("version = 'v1'"))
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `selectProfilesForMerge handles blank and sparse candidate rows`() = runBlocking {
        val queries = mutableListOf<String>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                firstArg<String>().also { queries.add(it) }
                if (queries.size == 1) {
                    "\n"
                } else {
                    """{"storage_key":"key-1"}"""
                }
            }

            val emptySelection = ProfileIngestionService.selectProfilesForMerge(
                ProfileMergeSelectionQuery(
                    organizationId = 42,
                    filters = ProfileQueryFilters(),
                    window = ProfileTimeWindow(fromMs = 1000, toMs = 2000),
                    maxProfiles = 0,
                ),
            )
            val sparseSelection = ProfileIngestionService.selectProfilesForMerge(
                ProfileMergeSelectionQuery(
                    organizationId = 42,
                    filters = ProfileQueryFilters(),
                    window = ProfileTimeWindow(fromMs = 1000, toMs = 2000),
                    maxProfiles = 1,
                ),
            )

            val candidate = sparseSelection.candidates.single()
            assertEquals(0, emptySelection.totalInWindow)
            assertTrue(emptySelection.candidates.isEmpty())
            assertEquals(0, sparseSelection.totalInWindow)
            assertEquals("", candidate.profileId)
            assertEquals("key-1", candidate.storageKey)
            assertEquals("", candidate.profileType)
            assertEquals("datadog", candidate.source)
            assertTrue(queries.first().contains("LIMIT 1"))
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getProfile and getProfileMeta map ClickHouse rows`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } answers {
                val sql = firstArg<String>()
                if (sql.contains("FORMAT JSONEachRow")) {
                    profileJsonRow()
                } else {
                    "key-1\tcpu"
                }
            }

            val profile = ProfileIngestionService.getProfile(organizationId = 42, profileId = "profile-1")
            val meta = ProfileIngestionService.getProfileMeta(organizationId = 42, profileId = "profile-1")

            assertEquals("profile-1", profile?.profileId)
            assertEquals("key-1", meta?.storageKey)
            assertEquals("cpu", meta?.profileType)
            assertEquals("datadog", meta?.source)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getProfileMeta defaults source for legacy tab separated rows`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "testdb"
            coEvery { ClickHouseClient.executeWithFormat(any(), any()) } returns "key-legacy\tjfr"

            val meta = ProfileIngestionService.getProfileMeta(organizationId = 42, profileId = "profile-legacy")

            assertEquals("key-legacy", meta?.storageKey)
            assertEquals("jfr", meta?.profileType)
            assertEquals("datadog", meta?.source)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    private fun profileJsonRow(): String = compactJson(
        """{"profile_id":"profile-1",""",
        """"host":"host-a","service":"api","env":"prod","version":"v1",""",
        """"runtime":"jvm","language":"java","profile_type":"cpu",""",
        """"start_time":"2026-01-01 00:00:00","end_time":"2026-01-01 00:00:01",""",
        """"duration_ns":1000,"storage_key":"key-1","tags":{"service":"api"},""",
        """"size_bytes":123,"source":"datadog"}""",
    )

    private fun profileServiceRollupJson(): String = compactJson(
        """{"service":"api","languages":["java"],"runtimes":["jvm"],"environments":["prod"],""",
        """"hostCount":2,"profileCount":3,"totalSizeBytes":900,""",
        """"firstSeen":"2026-01-01 00:00:00","lastSeen":"2026-01-01 00:10:00",""",
        """"avgDurationNs":123}""",
    )

    private fun compactJson(vararg parts: String): String = parts.joinToString("")
}
