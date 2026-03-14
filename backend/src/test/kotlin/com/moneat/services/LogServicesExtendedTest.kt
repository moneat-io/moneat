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

package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.logs.models.LogIngestEntry
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.repositories.LogRepository
import com.moneat.logs.services.LogService
import com.moneat.testsupport.queryBasedClickHouseHandler
import com.moneat.testsupport.withClickHouseMockServer
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Extended tests for LogService covering topValues, exportCsv,
 * getFilterOptionsWithCounts, getFilterOptions, getTagValues,
 * buildTagCondition, queryLogs edge cases, and aggregateLogs
 * error handling.
 */
class LogServicesExtendedTest {

    companion object {
        private const val AS_FIELD_VALUE = "AS field_value"
        private const val CODE_62_DB_EXCEPTION = "Code: 62. DB::Exception"
        private const val SELECT_COUNT = "SELECT count()"
        private const val FROM_2026_01_01 = "2026-01-01T00:00:00Z"
        private const val TO_2026_01_02 = "2026-01-02T00:00:00Z"
        private const val US_EAST = "us-east"
        private const val SERVICE_EQ_API = "service = 'api'"
        private const val SERVICE_NE_WORKER = "service != 'worker'"
        private const val ENV_NE_STAGING = "environment != 'staging'"
        private const val HAS_TAGS_REGION = "has(tags, 'region')"
        private const val FROM_2026_02_01_10 = "2026-02-01T10:00:00Z"
        private const val TO_2026_02_01_12 = "2026-02-01T12:00:00Z"
    }

    @BeforeTest
    fun setup() {
        ClickHouseClient.close()
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
    }

    private fun newService(repo: LogRepository = FakeLogRepository()): LogService {
        return LogService(repo)
    }

    private suspend fun LogService.topValuesWithEmptyFilters(
        orgId: Long,
        field: String,
        limit: Int = 10
    ) = topValues(
        organizationId = orgId,
        field = field,
        limit = limit,
        from = null,
        to = null,
        query = null,
        levels = emptyList(),
        service = null,
        environment = null,
        tags = emptyMap(),
        excludeService = null,
        excludeEnvironment = null,
        excludeContainerName = null,
        excludeTags = emptyMap()
    )

    private suspend fun LogService.exportCsvWithEmptyFilters(orgId: Long, limit: Int = 100) =
        exportCsv(
            organizationId = orgId,
            from = null,
            to = null,
            query = null,
            levels = emptyList(),
            service = null,
            environment = null,
            tags = emptyMap(),
            excludeService = null,
            excludeEnvironment = null,
            excludeContainerName = null,
            excludeTags = emptyMap(),
            limit = limit
        )

    private suspend fun LogService.aggregateLogsWithEmptyFilters(
        orgId: Long,
        from: String? = null,
        to: String? = null,
        interval: String = "1h",
        groupBy: String? = null
    ) = aggregateLogs(
        organizationId = orgId,
        from = from,
        to = to,
        interval = interval,
        query = null,
        levels = emptyList(),
        service = null,
        environment = null,
        tags = emptyMap(),
        excludeService = null,
        excludeEnvironment = null,
        excludeContainerName = null,
        excludeTags = emptyMap(),
        groupBy = groupBy
    )

    // ──── topValues ────

    @Test
    fun `topValues returns field values with counts`() = runBlocking {
        val handler = queryBasedClickHouseHandler(
            AS_FIELD_VALUE to
                """
                {"field_value":"api","cnt":100}
                {"field_value":"worker","cnt":50}
                """.trimIndent(),
            "$SELECT_COUNT AS cnt" to """{"cnt":200}"""
        )
        withClickHouseMockServer(handler) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))
            val result = service.topValuesWithEmptyFilters(1L, "service")
            assertEquals("service", result.field)
            assertEquals(2, result.values.size)
            assertEquals("api", result.values[0].value)
            assertEquals(100L, result.values[0].count)
            assertEquals(200L, result.totalCount)
        }
    }

    @Test
    fun `topValues returns empty on ClickHouse error`() = runBlocking {
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = CODE_62_DB_EXCEPTION)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.topValuesWithEmptyFilters(1L, "service")
            assertEquals("service", result.field)
            assertTrue(result.values.isEmpty())
            assertEquals(0L, result.totalCount)
        }
    }

    @Test
    fun `topValues uses tag key expression for non-standard fields`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        val handler = queryBasedClickHouseHandler(
            AS_FIELD_VALUE to """{"field_value":"$US_EAST","cnt":10}""",
            "$SELECT_COUNT" to """{"cnt":10}""",
            captureQueries = capturedQueries
        )
        withClickHouseMockServer(handler) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.topValuesWithEmptyFilters(1L, "region", 5)
            assertEquals("region", result.field)
            assertEquals(1, result.values.size)
            assertTrue(capturedQueries.any { it.contains("tags['region']") })
        }
    }

    @Test
    fun `topValues with filters applies conditions`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        val handler = queryBasedClickHouseHandler(
            AS_FIELD_VALUE to """{"field_value":"error","cnt":5}""",
            "$SELECT_COUNT" to """{"cnt":5}""",
            captureQueries = capturedQueries
        )
        withClickHouseMockServer(handler) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.topValues(
                organizationId = 1L,
                field = "level",
                limit = 10,
                from = FROM_2026_01_01,
                to = TO_2026_01_02,
                query = "database",
                levels = listOf("error"),
                service = "api",
                environment = "prod",
                tags = mapOf("region" to US_EAST),
                excludeService = "worker",
                excludeEnvironment = "staging",
                excludeContainerName = "test-container",
                excludeTags = mapOf("debug" to "true")
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains(SERVICE_EQ_API))
            assertTrue(allQueries.contains("environment = 'prod'"))
            assertTrue(allQueries.contains(SERVICE_NE_WORKER))
            assertTrue(allQueries.contains(ENV_NE_STAGING))
        }
    }

    // ──── exportCsv ────

    @Test
    fun `exportCsv returns CSV with header and rows`() = runBlocking {
        val csvRow =
            """
            {"timestamp":"2026-02-01 10:00:00","level":"error","service":"api","environment":"prod","host":"h1","message":"fail","container_name":"","trace_id":"t1","span_id":"s1","tags":"{}"}
            """.trimIndent()
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = csvRow)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val csv = service.exportCsvWithEmptyFilters(1L)
            assertTrue(csv.startsWith("timestamp,level,service,environment,host,message,"))
            assertTrue(csv.contains("error"))
            assertTrue(csv.contains("api"))
            assertTrue(csv.contains("prod"))
        }
    }

    @Test
    fun `exportCsv throws on ClickHouse error`() = runBlocking {
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = "Code: 62. DB::Exception: Syntax error")
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            try {
                service.exportCsvWithEmptyFilters(1L)
                assertTrue(false, "Should have thrown")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Failed to export logs"))
            }
        }
    }

    @Test
    fun `exportCsv escapes values with commas and quotes`() = runBlocking {
        val rowWithComma =
            """{"timestamp":"2026-02-01","level":"info","service":"api","environment":"prod","host":"h1",""" +
                """"message":"hello, \"world\"","container_name":"","trace_id":"","span_id":"","tags":"{}"}"""
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = rowWithComma)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val csv = service.exportCsvWithEmptyFilters(1L)
            // The message contains a comma so it should be quoted in CSV
            assertTrue(csv.contains("\"hello,"))
        }
    }

    // ──── getFilterOptions ────

    @Test
    fun `getFilterOptions returns services environments and tagKeys`() = runBlocking {
        val handler = queryBasedClickHouseHandler(
            "DISTINCT service" to "api\nworker\n",
            "DISTINCT environment" to "prod\nstaging\n",
            "tag_key" to "region\nversion\n"
        )
        withClickHouseMockServer(handler) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.getFilterOptions(
                organizationId = 1L,
                from = null,
                to = null
            )

            assertEquals(listOf("api", "worker"), result.services)
            assertEquals(listOf("prod", "staging"), result.environments)
            assertEquals(listOf("region", "version"), result.tagKeys)
            assertTrue(result.levels.contains("error"))
            assertTrue(result.levels.contains("info"))
        }
    }

    @Test
    fun `getFilterOptions handles ClickHouse error gracefully`() = runBlocking {
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = CODE_62_DB_EXCEPTION)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.getFilterOptions(
                organizationId = 1L,
                from = FROM_2026_01_01,
                to = TO_2026_01_02
            )

            assertTrue(result.services.isEmpty())
            assertTrue(result.environments.isEmpty())
            assertTrue(result.tagKeys.isEmpty())
        }
    }

    // ──── getFilterOptionsWithCounts ────

    @Test
    fun `getFilterOptionsWithCounts returns services and environments with counts`() = runBlocking {
        val handler = queryBasedClickHouseHandler(
            "service AS val" to
                """
                {"val":"api","cnt":100}
                {"val":"worker","cnt":50}
                """.trimIndent(),
            "environment AS val" to """{"val":"prod","cnt":200}""",
            "tag_key" to "region\n"
        )
        withClickHouseMockServer(handler) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.getFilterOptionsWithCounts(
                organizationId = 1L,
                from = FROM_2026_01_01,
                to = TO_2026_01_02
            )

            assertEquals(2, result.services.size)
            assertEquals("api", result.services[0].value)
            assertEquals(100L, result.services[0].count)
            assertEquals(1, result.environments.size)
            assertEquals("prod", result.environments[0].value)
            assertEquals(200L, result.environments[0].count)
            assertEquals(listOf("region"), result.tagKeys)
        }
    }

    // ──── getTagValues ────

    @Test
    fun `getTagValues returns distinct tag values`() = runBlocking {
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = "$US_EAST\nus-west\neu-west\n")
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.getTagValues(
                organizationId = 1L,
                key = "region",
                from = null,
                to = null
            )

            assertEquals("region", result.key)
            assertEquals(listOf(US_EAST, "us-west", "eu-west"), result.values)
        }
    }

    @Test
    fun `getTagValues returns empty for blank key`() = runBlocking {
        val service = newService()
        val result = service.getTagValues(
            organizationId = 1L,
            key = "   ",
            from = null,
            to = null
        )
        assertEquals(emptyList(), result.values)
    }

    @Test
    fun `getTagValues maps status to level for top-level fields`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(
            queryBasedClickHouseHandler(
                defaultBody = "info\nerror\n",
                captureQueries = capturedQueries
            )
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.getTagValues(
                organizationId = 1L,
                key = "status",
                from = null,
                to = null
            )

            assertEquals("status", result.key)
            // The query should use toString(level) since status maps to level (an Enum8)
            assertTrue(capturedQueries.any { it.contains("toString(level)") })
        }
    }

    @Test
    fun `getTagValues with time range applies time filters`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(
            queryBasedClickHouseHandler(
                defaultBody = "val1\n",
                captureQueries = capturedQueries
            )
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.getTagValues(
                organizationId = 1L,
                key = "service",
                from = FROM_2026_01_01,
                to = TO_2026_01_02
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains("fromUnixTimestamp64Milli"))
        }
    }

    // ──── buildTagCondition ────

    @Test
    fun `buildTagCondition returns empty for blank key`() {
        val service = newService()
        assertEquals("", service.buildTagCondition("", "value"))
    }

    @Test
    fun `buildTagCondition handles top-level field`() {
        val service = newService()
        val result = service.buildTagCondition("service", "api")
        assertEquals(SERVICE_EQ_API, result)
    }

    @Test
    fun `buildTagCondition handles top-level field with exclude`() {
        val service = newService()
        val result = service.buildTagCondition("service", "api", exclude = true)
        assertEquals("service != 'api'", result)
    }

    @Test
    fun `buildTagCondition maps status to level with toString`() {
        val service = newService()
        val result = service.buildTagCondition("status", "error")
        assertEquals("toString(level) = 'error'", result)
    }

    @Test
    fun `buildTagCondition handles actual tag key`() {
        val service = newService()
        val result = service.buildTagCondition("region", US_EAST)
        assertTrue(result.contains(HAS_TAGS_REGION))
        assertTrue(result.contains("tags['region'] = '$US_EAST'"))
    }

    @Test
    fun `buildTagCondition handles actual tag key with exclude`() {
        val service = newService()
        val result = service.buildTagCondition("region", US_EAST, exclude = true)
        assertTrue(result.startsWith("NOT"))
        assertTrue(result.contains(HAS_TAGS_REGION))
    }

    @Test
    fun `buildTagCondition handles enum field source with toString`() {
        val service = newService()
        val result = service.buildTagCondition("source", "sdk")
        assertEquals("toString(source) = 'sdk'", result)
    }

    @Test
    fun `buildTagCondition parses malformed tag with boolean operators`() {
        val service = newService()
        // A tag value containing "OR" should be parsed as a query
        val result = service.buildTagCondition("service", "api OR worker")
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `buildTagCondition parses tag key starting with dash as query`() {
        val service = newService()
        val result = service.buildTagCondition("-service", "api")
        assertTrue(result.isNotBlank())
    }

    // ──── queryLogs edge cases ────

    private fun queryLogsEmptyHandler(captureQueries: MutableList<String>? = null) =
        queryBasedClickHouseHandler(
            "toString(log_id) AS log_id" to "",
            "$SELECT_COUNT" to """{"count":0}""",
            captureQueries = captureQueries
        )

    @Test
    fun `queryLogs with time range and level filters`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(queryLogsEmptyHandler(capturedQueries)) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.queryLogs(
                organizationId = 42L,
                request = LogQueryRequest(
                    from = FROM_2026_01_01,
                    to = TO_2026_01_02,
                    levels = listOf("error", "warning"),
                    service = "api",
                    environment = "prod",
                    containerName = "my-container"
                )
            )

            assertTrue(result.logs.isEmpty())
            assertFalse(result.hasMore)
            assertEquals(0L, result.totalCount)
            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains(SERVICE_EQ_API))
            assertTrue(allQueries.contains("environment = 'prod'"))
            assertTrue(allQueries.contains("container_name = 'my-container'"))
            assertTrue(allQueries.contains("level IN"))
        }
    }

    @Test
    fun `queryLogs with exclude filters`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(queryLogsEmptyHandler(capturedQueries)) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.queryLogs(
                organizationId = 42L,
                request = LogQueryRequest(
                    excludeService = "worker",
                    excludeEnvironment = "staging",
                    excludeContainerName = "debug-container",
                    excludeTags = mapOf("debug" to "true")
                )
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains(SERVICE_NE_WORKER))
            assertTrue(allQueries.contains(ENV_NE_STAGING))
            assertTrue(allQueries.contains("container_name != 'debug-container'"))
        }
    }

    @Test
    fun `queryLogs with query string uses parser`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(queryLogsEmptyHandler(capturedQueries)) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.queryLogs(
                organizationId = 42L,
                request = LogQueryRequest(query = "service:api error")
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains(SERVICE_EQ_API))
        }
    }

    @Test
    fun `queryLogs with tag filters includes tag conditions`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(queryLogsEmptyHandler(capturedQueries)) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.queryLogs(
                organizationId = 42L,
                request = LogQueryRequest(
                    tags = mapOf("region" to US_EAST)
                )
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains(HAS_TAGS_REGION))
            assertTrue(allQueries.contains("tags['region'] = '$US_EAST'"))
        }
    }

    @Test
    fun `queryLogs with valid systemId applies system filter`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(queryLogsEmptyHandler(capturedQueries)) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.queryLogs(
                organizationId = 42L,
                request = LogQueryRequest(
                    systemId = "11111111-1111-1111-1111-111111111111"
                )
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains("system_id = toUUID('11111111-1111-1111-1111-111111111111')"))
        }
    }

    @Test
    fun `queryLogs with hostId applies host filter`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(queryLogsEmptyHandler(capturedQueries)) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.queryLogs(
                organizationId = 42L,
                request = LogQueryRequest(hostId = 7)
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains("tags['host_id'] = '7'"))
        }
    }

    @Test
    fun `queryLogs ClickHouse error in main query throws`() = runBlocking {
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = "Code: 62. DB::Exception: Syntax error")
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            try {
                service.queryLogs(
                    organizationId = 42L,
                    request = LogQueryRequest()
                )
                assertTrue(false, "Should have thrown")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Failed to query logs"))
            }
        }
    }

    // ──── aggregateLogs edge cases ────

    @Test
    fun `aggregateLogs with no groupBy returns _total buckets`() = runBlocking {
        val bucketRows =
            """
            {"bucket":"2026-02-01T10:00:00Z","cnt":10}
            {"bucket":"2026-02-01T11:00:00Z","cnt":20}
            """.trimIndent()
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = bucketRows)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.aggregateLogsWithEmptyFilters(
                1L,
                from = FROM_2026_02_01_10,
                to = TO_2026_02_01_12
            )
            assertEquals(2, result.buckets.size)
            assertEquals(30L, result.totalCount)
            // No groupBy means groups map is empty
            assertTrue(result.buckets[0].groups.isEmpty())
        }
    }

    @Test
    fun `aggregateLogs returns empty on ClickHouse error`() = runBlocking {
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = CODE_62_DB_EXCEPTION)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.aggregateLogsWithEmptyFilters(1L)
            assertTrue(result.buckets.isEmpty())
            assertEquals(0L, result.totalCount)
        }
    }

    @Test
    fun `aggregateLogs with exclude filters`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = "", captureQueries = capturedQueries)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            service.aggregateLogs(
                organizationId = 1L,
                from = null,
                to = null,
                interval = "1h",
                query = null,
                levels = emptyList(),
                service = null,
                environment = null,
                tags = emptyMap(),
                excludeService = "worker",
                excludeEnvironment = "staging",
                excludeContainerName = "test-ctr",
                excludeTags = mapOf("debug" to "true"),
                groupBy = null
            )

            val allQueries = capturedQueries.joinToString("\n")
            assertTrue(allQueries.contains(SERVICE_NE_WORKER))
            assertTrue(allQueries.contains(ENV_NE_STAGING))
            assertTrue(allQueries.contains("container_name != 'test-ctr'"))
        }
    }

    @Test
    fun `aggregateLogs ignores invalid groupBy values`() = runBlocking {
        val capturedQueries = mutableListOf<String>()
        withClickHouseMockServer(
            queryBasedClickHouseHandler(
                defaultBody = """{"bucket":"$FROM_2026_02_01_10","cnt":5}""",
                captureQueries = capturedQueries
            )
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.aggregateLogsWithEmptyFilters(
                1L,
                from = FROM_2026_02_01_10,
                to = TO_2026_02_01_12,
                groupBy = "invalidField"
            )
            // Invalid groupBy is ignored — treated as null
            assertTrue(result.buckets[0].groups.isEmpty())
            // The SQL should NOT contain "GROUP BY bucket, group_value"
            val allQueries = capturedQueries.joinToString("\n")
            assertFalse(allQueries.contains("group_value"))
        }
    }

    @Test
    fun `aggregateLogs with groupBy service`() = runBlocking {
        val groupRows =
            """
            {"bucket":"2026-02-01T10:00:00Z","group_value":"api","cnt":10}
            {"bucket":"2026-02-01T10:00:00Z","group_value":"worker","cnt":5}
            """.trimIndent()
        withClickHouseMockServer(
            queryBasedClickHouseHandler(defaultBody = groupRows)
        ) { server ->
            val service = newService(ClickHouseLogRepository(server.baseUrl))

            val result = service.aggregateLogsWithEmptyFilters(
                1L,
                from = FROM_2026_02_01_10,
                to = TO_2026_02_01_12,
                groupBy = "service"
            )
            assertEquals(1, result.buckets.size)
            assertEquals(15L, result.buckets[0].count)
            assertEquals(10L, result.buckets[0].groups["api"])
            assertEquals(5L, result.buckets[0].groups["worker"])
        }
    }

    // ──── parseOtlpJson edge cases ────

    @Test
    fun `parseOtlpJson handles int and double anyValue types`() {
        val service = newService()
        val payload = """
        {
            "resourceLogs": [{
                "resource": {"attributes": []},
                "scopeLogs": [{
                    "logRecords": [{
                        "body": {"stringValue": "test"},
                        "attributes": [
                            {"key": "count", "value": {"intValue": "42"}},
                            {"key": "rate", "value": {"doubleValue": "3.14"}},
                            {"key": "flag", "value": {"boolValue": "true"}}
                        ]
                    }]
                }]
            }]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(payload)
        assertEquals(1, result.size)
        assertEquals("42", result[0].tags?.get("count"))
        assertEquals("3.14", result[0].tags?.get("rate"))
        assertEquals("true", result[0].tags?.get("flag"))
    }

    @Test
    fun `parseOtlpJson uses observedTimeUnixNano fallback`() {
        val service = newService()
        val payload = """
        {
            "resourceLogs": [{
                "resource": {"attributes": []},
                "scopeLogs": [{
                    "logRecords": [{
                        "observedTimeUnixNano": 1738000000000000000,
                        "body": {"stringValue": "observed time log"}
                    }]
                }]
            }]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(payload)
        assertEquals(1, result.size)
        assertEquals(1738000000000L, result[0].timestampMs)
    }

    @Test
    fun `parseOtlpJson handles empty scopeLogs`() {
        val service = newService()
        val payload = """
        {
            "resourceLogs": [{
                "resource": {"attributes": []},
                "scopeLogs": []
            }]
        }
        """.trimIndent()

        val result = service.parseOtlpJson(payload)
        assertTrue(result.isEmpty())
    }

    // ──── liveChannel ────

    @Test
    fun `liveChannel returns correct channel name`() {
        val service = newService()
        assertEquals("log:live:42", service.liveChannel(42L))
        assertEquals("log:live:0", service.liveChannel(0L))
    }

    // ──── estimateBillableBytes edge cases ────

    @Test
    fun `estimateBillableBytes skips entries with blank message`() {
        val service = newService()
        val entries = listOf(
            LogIngestEntry(message = "   ", body = "body"),
            LogIngestEntry(message = "valid", body = "text")
        )
        // blank message entry is skipped by normalizeSdkEntry
        assertEquals(9L, service.estimateBillableBytes(entries))
    }

    // ──── autoInterval edge cases ────

    @Test
    fun `autoInterval exactly at boundary returns correct interval`() {
        val service = newService()
        // Exactly 6 hours
        assertEquals("5m", service.autoInterval(0L, 21_600_000L))
        // Exactly 24 hours
        assertEquals("15m", service.autoInterval(0L, 86_400_000L))
        // Exactly 7 days
        assertEquals("1h", service.autoInterval(0L, 604_800_000L))
    }

    // ──── Helper classes ────

    /**
     * Simple fake LogRepository that returns empty results.
     */
    private class FakeLogRepository : LogRepository {
        override suspend fun executeClickHouseInsert(sql: String): Boolean = true
        override suspend fun executeClickHouseQuery(sql: String): String = ""
    }

    /**
     * LogRepository backed by a MockHttpServer via ClickHouseClient.
     */
    private class ClickHouseLogRepository(
        @Suppress("unused") private val baseUrl: String
    ) : LogRepository {
        override suspend fun executeClickHouseInsert(sql: String): Boolean {
            val response = ClickHouseClient.execute(sql)
            return response.status.value in 200..299
        }

        override suspend fun executeClickHouseQuery(sql: String): String {
            val response = ClickHouseClient.execute(sql)
            return response.bodyAsText()
        }
    }
}
