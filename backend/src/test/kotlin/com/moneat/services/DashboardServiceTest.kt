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
import com.moneat.models.Projects
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardServiceTest {
    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_dashboard_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction(db!!) {
                SchemaUtils.create(Projects)
            }
        }
    }

    @Test
    fun `getIssues applies pagination and status filter and parses rows`() =
        runBlocking {
            val queries = Collections.synchronizedList(mutableListOf<String>())
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                queries += query
                if (query.contains("FROM test.events e") && query.contains("GROUP BY issue_id")) {
                    exchange.respond(
                        200,
                        """
                    {"issue_id":"issue-1","title":"Null pointer","culprit":"Main.kt","level":"error","platform":"kotlin","first_seen":"2026-02-01T01:00:00.000Z","last_seen":"2026-02-02T01:00:00.000Z","event_count":12,"user_count":4,"status":"resolved"}
                        """.trimIndent(),
                        contentType = "text/plain"
                    )
                } else {
                    exchange.respond(200, "", contentType = "text/plain")
                }
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

                val service = DashboardService()
                val issues = service.getIssues(projectId = -1, page = 3, limit = 10, status = "resolved")

                assertEquals(1, issues.size)
                assertEquals("issue-1", issues.first().id)
                assertEquals("resolved", issues.first().status)
                assertTrue(queries.any { it.contains("LIMIT 10 OFFSET 20") })
                assertTrue(queries.any { it.contains("AND status = 'resolved'") })
            }
        }

    @Test
    fun `getIssue returns detail with latest event and demo project name`() =
        runBlocking {
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains("FROM test.issues") && query.contains("WHERE issue_id = 'issue-1'") -> {
                        exchange.respond(200, """{"project_id":-1}""", contentType = "text/plain")
                    }

                    query.contains("FROM test.events e") && query.contains("fingerprint") -> {
                        exchange.respond(
                            200,
                            """
                        {"issue_id":"issue-1","title":"Crash","culprit":"Api.kt","level":"fatal","platform":"android","first_seen":"2026-02-01T00:00:00.000Z","last_seen":"2026-02-03T00:00:00.000Z","event_count":8,"user_count":2,"status":"unresolved","fingerprint":["NullPointerException","Api.kt"]}
                            """.trimIndent(),
                            contentType = "text/plain"
                        )
                    }

                    query.contains("FROM test.events") && query.contains("ORDER BY timestamp DESC") -> {
                        exchange.respond(
                            200,
                            """
                        {"event_id":"evt-1","timestamp":"2026-02-03T00:00:00.000Z","message":"boom","platform":"android","level":"fatal","environment":"prod","release":"1.0.0","user_id":"u-1","user_email":"u@test.com","user_username":"user","tags":{"env":"prod"},"contexts":"{\"trace\":{\"trace_id\":\"t1\"}}","stack_trace":"stack","breadcrumbs":"[]"}
                            """.trimIndent(),
                            contentType = "text/plain"
                        )
                    }

                    else -> {
                        exchange.respond(200, "", contentType = "text/plain")
                    }
                }
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val service = DashboardService()
                val issue = service.getIssue("issue-1")

                assertNotNull(issue)
                assertEquals("issue-1", issue.id)
                assertEquals(-1, issue.projectId)
                assertEquals("Android", issue.projectName)
                assertEquals(2, issue.fingerprint.size)
                assertNotNull(issue.latestEvent)
                assertEquals("evt-1", issue.latestEvent.eventId)
                assertEquals("prod", issue.latestEvent.tags["env"])
            }
        }

    @Test
    fun `getTraceDetails assembles span waterfall timing`() =
        runBlocking {
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                if (query.contains("FROM test.spans") && query.contains("trace_id = 'trace-1'")) {
                    exchange.respond(
                        200,
                        """
                    {"span_id":"s1","parent_span_id":"","trace_id":"trace-1","transaction_id":"tx-1","op":"http.server","description":"GET /","start_ts_ms":"1000","end_ts_ms":"2500","duration_ms":"1500","status":"ok","tags":{"component":"api"},"data":"{}"}
                    {"span_id":"s2","parent_span_id":"s1","trace_id":"trace-1","transaction_id":"tx-1","op":"db","description":"SELECT","start_ts_ms":"2600","end_ts_ms":"5000","duration_ms":"2400","status":"ok","tags":{"db":"main"},"data":"{}"}
                        """.trimIndent(),
                        contentType = "text/plain"
                    )
                } else {
                    exchange.respond(200, "", contentType = "text/plain")
                }
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val service = DashboardService()
                val trace = service.getTraceDetails(projectId = -1, traceId = "trace-1")

                assertNotNull(trace)
                assertEquals("trace-1", trace.traceId)
                assertEquals(2, trace.spans.size)
                assertEquals(1.0, trace.startTimestamp)
                assertEquals(5.0, trace.endTimestamp)
                assertEquals(4000.0, trace.duration)
            }
        }

    @Test
    fun `getProjectIdForEvent normalizes compact uuid`() =
        runBlocking {
            val queries = Collections.synchronizedList(mutableListOf<String>())
            val compactEventId = "0123456789abcdef0123456789abcdef"
            val normalizedEventId = "01234567-89ab-cdef-0123-456789abcdef"

            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                queries += query
                if (query.contains(normalizedEventId)) {
                    exchange.respond(200, """{"project_id":123}""", contentType = "text/plain")
                } else {
                    exchange.respond(200, "", contentType = "text/plain")
                }
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val service = DashboardService()
                val projectId = service.getProjectIdForEvent(compactEventId)

                assertEquals(123L, projectId)
                assertTrue(queries.any { it.contains(normalizedEventId) })
            }
        }
}
