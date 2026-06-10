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
import com.moneat.logs.models.LogAnalyticsFilters
import com.moneat.logs.models.LogPatternRequest
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.repositories.LogRepository
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.logs.services.LogService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogServiceExecutionTest {
    private companion object {
        private const val ANCHOR_LOG_ID = "00000000-0000-0000-0000-000000000123"
        private const val FROM_MS = "1704067200000"
        private const val TO_MS = "1704067800000"
        private const val PATTERN_MESSAGE = "Payment 123 failed for user usr_abc123"
        private const val NORMALIZED_PATTERN = "Payment <int> failed for user <id>"
        private const val NORMALIZED_PATTERN_LIKE = "Payment % failed for user %"
        private const val ORDER_PATTERN_LIKE = "Order % failed for user %"
    }

    @BeforeTest
    fun setup() {
        ClickHouseClient.close()
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
    }

    @Test
    fun `queryLogs filters by host and trace id`() =
        runBlocking {
            val repository =
                RecordingLogRepository { sql ->
                    when {
                        sql.contains("SELECT") && sql.contains("toString(log_id) AS log_id_str") -> ""
                        else -> error("Unexpected query: $sql")
                    }
                }

            val result =
                LogService(repository).queryLogs(
                    organizationId = 42L,
                    request = LogQueryRequest(host = "checkout-1", traceId = "trace-abc")
                )

            assertTrue(result.logs.isEmpty())
            assertFalse(result.hasMore)
            assertEquals(null, result.totalCount)

            val selectQuery = repository.queries.first { it.contains("toString(log_id) AS log_id_str") }
            assertTrue(selectQuery.contains("host = 'checkout-1'"))
            assertTrue(selectQuery.contains("trace_id = 'trace-abc'"))
        }

    @Test
    fun `queryLogs filters by structural message pattern`() =
        runBlocking {
            val repository =
                RecordingLogRepository { sql ->
                    when {
                        sql.contains("SELECT") && sql.contains("toString(log_id) AS log_id_str") -> ""
                        sql.contains("SELECT count() as count") -> """{"count":0}"""
                        else -> error("Unexpected query: $sql")
                    }
                }

            LogService(repository).queryLogs(
                organizationId = 42L,
                request = LogQueryRequest(messagePattern = "Order <int> failed for user <id>")
            )

            val selectQuery = repository.queries.first { it.contains("toString(log_id) AS log_id_str") }
            assertTrue(selectQuery.contains("message LIKE {messagePattern:String}"))
            assertTrue(selectQuery.contains("{messagePattern:String}"))
            assertFalse(selectQuery.contains("Order <int> failed for user <id>"))
            assertEquals(
                mapOf("messagePattern" to ORDER_PATTERN_LIKE),
                repository.parameters.first()
            )
        }

    @Test
    fun `getLogPattern returns pattern rollups from ClickHouse aggregates`() =
        runBlocking {
            val repository =
                RecordingLogRepository { sql ->
                    when {
                        sql.contains("AS first_seen_ms") -> {
                            """
                            {"cnt":3,"level_value":"error","first_seen_ms":1780272000000,"last_seen_ms":1780358400000}
                            """.trimIndent()
                        }

                        sql.contains("AS previous_count") -> """{"previous_count":2}"""
                        sql.contains("AS bucket_index") -> {
                            """
                            {"bucket_index":0,"cnt":1}
                            {"bucket_index":2,"cnt":2}
                            {"bucket_index":3,"cnt":4}
                            """.trimIndent()
                        }

                        sql.contains("service AS value") -> {
                            """
                            {"value":"checkout","cnt":2}
                            {"value":"worker","cnt":1}
                            """.trimIndent()
                        }

                        sql.contains("host AS value") -> {
                            """
                            {"value":"checkout-1","cnt":2}
                            {"value":"checkout-2","cnt":1}
                            """.trimIndent()
                        }

                        else -> error("Unexpected query: $sql")
                    }
                }

            val result =
                LogService(repository).getLogPattern(
                    organizationId = 42L,
                    request = LogPatternRequest(
                        message = "Order 123 failed for user usr_abcdef",
                        service = "checkout",
                        from = "2026-06-01T00:00:00Z",
                        to = "2026-06-02T00:00:00Z"
                    )
                )

            assertEquals("Order <int> failed for user <id>", result.pattern)
            assertEquals("error", result.level)
            assertEquals(3L, result.count)
            assertEquals("24h", result.windowLabel)
            assertEquals("2026-06-01T00:00:00Z", result.firstSeen)
            assertEquals("2026-06-02T00:00:00Z", result.lastSeen)
            assertEquals(50, result.trendPct)
            assertEquals(listOf(1L, 0L, 6L), result.sparkline)
            assertEquals("checkout", result.topServices.first().value)
            assertEquals(2L, result.topServices.first().count)
            assertEquals("checkout-1", result.topHosts.first().value)

            val statsQuery = repository.queries.first { it.contains("AS first_seen_ms") }
            val statsParameters = repository.parameters[repository.queries.indexOf(statsQuery)]
            assertTrue(statsQuery.contains("organization_id = 42"))
            assertTrue(statsQuery.contains("service = {service:String}"))
            assertTrue(statsQuery.contains("message LIKE {messagePattern:String}"))
            assertEquals("checkout", statsParameters["service"])
            assertEquals(ORDER_PATTERN_LIKE, statsParameters["messagePattern"])
        }

    @Test
    fun `getLogPattern resolves anchor log and ignores malformed rollup rows`() =
        runBlocking {
            val repository =
                RecordingLogRepository { sql ->
                    when {
                        sql.contains("SELECT message") -> """{"message":"$PATTERN_MESSAGE"}"""
                        sql.contains("AS first_seen_ms") -> {
                            """
                            not-json
                            {"cnt":1,"level_value":"warn","first_seen_ms":1704067200000,"last_seen_ms":1704067800000}
                            """.trimIndent()
                        }

                        sql.contains("AS previous_count") -> "Code: 62. DB::Exception"
                        sql.contains("AS bucket_index") -> {
                            """
                            {"bucket_index":-1,"cnt":2}
                            {"bucket_index":9,"cnt":3}
                            """.trimIndent()
                        }

                        sql.contains("service AS value") -> {
                            """
                            {"value":"","cnt":9}
                            {"value":"checkout","cnt":1}
                            """.trimIndent()
                        }

                        sql.contains("host AS value") -> "Code: 62. DB::Exception"
                        else -> error("Unexpected query: $sql")
                    }
                }

            val result =
                LogService(repository).getLogPattern(
                    organizationId = 42L,
                    request = LogPatternRequest(
                        logId = ANCHOR_LOG_ID,
                        service = "checkout",
                        from = FROM_MS,
                        to = TO_MS
                    )
                )

            assertEquals(NORMALIZED_PATTERN, result.pattern)
            assertEquals("warn", result.level)
            assertEquals(1L, result.count)
            assertEquals("10m", result.windowLabel)
            assertEquals("2024-01-01T00:00:00Z", result.firstSeen)
            assertEquals("2024-01-01T00:10:00Z", result.lastSeen)
            assertNull(result.trendPct)
            assertEquals(listOf(2L, 0L, 3L), result.sparkline)
            assertEquals(listOf("checkout"), result.topServices.map { it.value })
            assertTrue(result.topHosts.isEmpty())

            val anchorQuery = repository.queries.first { it.contains("SELECT message") }
            val anchorParams = repository.parameters[repository.queries.indexOf(anchorQuery)]
            assertEquals(mapOf("logId" to ANCHOR_LOG_ID), anchorParams)

            val statsQuery = repository.queries.first { it.contains("AS first_seen_ms") }
            val statsParams = repository.parameters[repository.queries.indexOf(statsQuery)]
            assertTrue(statsQuery.contains("service = {service:String}"))
            assertEquals("checkout", statsParams["service"])
            assertEquals(NORMALIZED_PATTERN_LIKE, statsParams["messagePattern"])
        }

    @Test
    fun `getLogPattern returns empty response for invalid anchor`() =
        runBlocking {
            val repository =
                RecordingLogRepository { sql ->
                    error("Unexpected query: $sql")
                }

            val result =
                LogService(repository).getLogPattern(
                    organizationId = 42L,
                    request = LogPatternRequest(
                        logId = "not-a-uuid",
                        message = "   ",
                        from = FROM_MS,
                        to = TO_MS
                    )
                )

            assertEquals("", result.pattern)
            assertEquals("info", result.level)
            assertEquals(0L, result.count)
            assertEquals("10m", result.windowLabel)
            assertNull(result.trendPct)
            assertEquals(listOf(0L, 0L, 0L), result.sparkline)
            assertTrue(result.topServices.isEmpty())
            assertTrue(result.topHosts.isEmpty())
            assertTrue(repository.queries.isEmpty())
        }

    @Test
    fun `queryLogs deserializes clickhouse rows and computes pagination`() =
        runBlocking {
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains("toString(log_id) AS log_id") -> {
                        exchange.respond(
                            200,
                            """
                        {"log_id_str":"00000000-0000-0000-0000-000000000001","timestamp_ms":1738371600000,"level_text":"WARNING","message":"first","body":"first body","service":"api","environment":"prod","host":"host-1","source_text":"agent_stdout","container_name":"","container_id":"","container_image":"","trace_id":"","span_id":"","tags":"{\"region\":\"us-east\"}","resource_attributes":"{\"deployment.environment\":\"prod\"}","system_id_text":"00000000-0000-0000-0000-000000000000"}
                        {"log_id_str":"00000000-0000-0000-0000-000000000000","timestamp_ms":1738371540000,"level_text":"error","message":"second","body":"second body","service":"api","environment":"prod","host":"host-1","source_text":"sdk","container_name":"","container_id":"","container_image":"","trace_id":"","span_id":"","tags":"{}","resource_attributes":"{}","system_id_text":"11111111-1111-1111-1111-111111111111"}
                            """.trimIndent(),
                            contentType = "text/plain"
                        )
                    }

                    query.contains("SELECT count() as count") -> {
                        exchange.respond(200, "{\"count\":5}", contentType = "text/plain")
                    }

                    else -> {
                        exchange.respond(500, "unexpected query", contentType = "text/plain")
                    }
                }
            }.use { server ->
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val result =
                    LogService(LogRepositoryImpl()).queryLogs(
                        organizationId = 42L,
                        request = LogQueryRequest(limit = 1)
                    )

                assertEquals(1, result.logs.size)
                assertTrue(result.hasMore)
                assertNotNull(result.nextCursor)
                assertNull(result.totalCount)

                val first = result.logs.first()
                assertEquals("warn", first.level)
                assertEquals("agent_stdout", first.source)
                assertEquals("us-east", first.tags["region"])
                assertNull(first.systemId)
            }
        }

    @Test
    fun `queryLogs returns empty result for invalid system id filter`() =
        runBlocking {
            val result =
                LogService(LogRepositoryImpl()).queryLogs(
                    organizationId = 42L,
                    request = LogQueryRequest(systemId = "not-a-uuid")
                )

            assertTrue(result.logs.isEmpty())
            assertFalse(result.hasMore)
            assertNull(result.nextCursor)
            assertEquals(0L, result.totalCount)
        }

    @Test
    fun `aggregateLogs groups counts by level and auto-selects interval`() =
        runBlocking {
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                if (query.contains("GROUP BY bucket, group_value")) {
                    exchange.respond(
                        200,
                        """
                    {"bucket":"2026-02-01 10:00:00","group_value":"error","cnt":2}
                    {"bucket":"2026-02-01 10:00:00","group_value":"warn","cnt":1}
                    {"bucket":"2026-02-01 11:00:00","group_value":"error","cnt":3}
                        """.trimIndent(),
                        contentType = "text/plain"
                    )
                } else {
                    exchange.respond(500, "unexpected query", contentType = "text/plain")
                }
            }.use { server ->
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val result =
                    LogService(LogRepositoryImpl()).aggregateLogs(
                        organizationId = 7L,
                        filters = LogAnalyticsFilters(
                            from = "2026-02-01T10:00:00Z",
                            to = "2026-02-01T12:00:00Z"
                        ),
                        interval = "auto",
                        groupBy = "level"
                    )

                assertEquals("5m", result.interval)
                assertEquals(6L, result.totalCount)
                assertEquals(2, result.buckets.size)
                assertEquals(3L, result.buckets[0].count)
                assertEquals(2L, result.buckets[0].groups["error"])
                assertEquals(1L, result.buckets[0].groups["warn"])
            }
        }

    @Test
    fun `analytics queries pass structural message pattern as ClickHouse parameter`() =
        runBlocking {
            val repository =
                RecordingLogRepository { sql ->
                    when {
                        sql.contains("AS field_value") -> """{"field_value":"checkout","cnt":4}"""
                        sql.contains("SELECT count() AS cnt") -> """{"cnt":4}"""
                        sql.contains("GROUP BY bucket") -> """{"bucket":"2024-01-01T00:00:00Z","cnt":4}"""
                        else -> error("Unexpected query: $sql")
                    }
                }
            val service = LogService(repository)
            val filters =
                LogAnalyticsFilters(
                    from = FROM_MS,
                    to = TO_MS,
                    host = "checkout-1",
                    traceId = "trace-abc",
                    messagePattern = NORMALIZED_PATTERN
                )

            val topValues = service.topValues(organizationId = 42L, field = "service", limit = 10, filters = filters)
            val aggregates =
                service.aggregateLogs(
                    organizationId = 42L,
                    filters = filters,
                    interval = "1h",
                    groupBy = null
                )

            assertEquals("checkout", topValues.values.single().value)
            assertEquals(4L, topValues.totalCount)
            assertEquals(4L, aggregates.totalCount)

            val allQueries = repository.queries.joinToString("\n")
            assertTrue(allQueries.contains("host = 'checkout-1'"))
            assertTrue(allQueries.contains("trace_id = 'trace-abc'"))
            assertTrue(allQueries.contains("{messagePattern:String}"))
            assertFalse(allQueries.contains(NORMALIZED_PATTERN))
            assertTrue(repository.parameters.all { it["messagePattern"] == NORMALIZED_PATTERN_LIKE })
        }

    private class RecordingLogRepository(
        private val responder: (String) -> String
    ) : LogRepository {
        val queries = mutableListOf<String>()
        val parameters = mutableListOf<Map<String, String>>()

        override suspend fun executeClickHouseInsert(sql: String): Boolean = true

        override suspend fun executeClickHouseQuery(sql: String): String {
            queries += sql
            parameters += emptyMap()
            return responder(sql)
        }

        override suspend fun executeClickHouseQuery(
            sql: String,
            queryParameters: Map<String, String>
        ): String {
            queries += sql
            parameters += queryParameters
            return responder(sql)
        }
    }
}
