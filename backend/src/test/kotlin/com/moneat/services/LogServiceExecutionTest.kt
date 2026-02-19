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
import com.moneat.models.LogQueryRequest
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
    @BeforeTest
    fun setup() {
        ClickHouseClient.close()
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
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
                        {"log_id":"00000000-0000-0000-0000-000000000001","timestamp_formatted":"2026-02-01T01:00:00.000Z","timestamp_ms":1738371600000,"level_text":"WARNING","message":"first","body":"first body","service":"api","environment":"prod","host":"host-1","source_text":"agent_stdout","container_name":"","container_id":"","container_image":"","trace_id":"","span_id":"","tags":"{\"region\":\"us-east\"}","resource_attributes":{"deployment.environment":"prod"},"system_id_text":"00000000-0000-0000-0000-000000000000"}
                        {"log_id":"00000000-0000-0000-0000-000000000000","timestamp_formatted":"2026-02-01T00:59:00.000Z","timestamp_ms":1738371540000,"level_text":"error","message":"second","body":"second body","service":"api","environment":"prod","host":"host-1","source_text":"sdk","container_name":"","container_id":"","container_image":"","trace_id":"","span_id":"","tags":"{}","resource_attributes":"{}","system_id_text":"11111111-1111-1111-1111-111111111111"}
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
                    LogService().queryLogs(
                        projectId = 42,
                        request = LogQueryRequest(limit = 1)
                    )

                assertEquals(1, result.logs.size)
                assertTrue(result.hasMore)
                assertNotNull(result.nextCursor)
                assertEquals(5L, result.totalCount)

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
                LogService().queryLogs(
                    projectId = 42,
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
                    LogService().aggregateLogs(
                        projectId = 7,
                        from = "2026-02-01T10:00:00Z",
                        to = "2026-02-01T12:00:00Z",
                        interval = "auto",
                        query = null,
                        levels = emptyList(),
                        service = null,
                        environment = null,
                        tags = emptyMap(),
                        excludeService = null,
                        excludeEnvironment = null,
                        excludeContainerName = null,
                        excludeTags = emptyMap(),
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
}
