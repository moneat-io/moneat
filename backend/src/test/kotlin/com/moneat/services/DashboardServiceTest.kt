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

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.config.ClickHouseClient
import com.moneat.events.services.DashboardService
import com.moneat.events.services.IssueListQuery
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardServiceTest {
    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_dashboard_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
            IssueStatuses,
            Subscriptions,
            PricingTierConfigs
        )
    }

    @Test
    fun `getIssues applies pagination and status filter and parses rows`() =
        runBlocking {
            val queries = Collections.synchronizedList(mutableListOf<String>())
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                queries += query
                if (query.contains("events e") && query.contains("GROUP BY issue_id")) {
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

                // Insert PG status row so the overlay returns "resolved"
                transaction {
                    Organizations.insert {
                        it[id] = 1
                        it[name] = "Test Org"
                        it[slug] = "test-org"
                    }
                    Projects.insert {
                        it[id] = -1
                        it[organization_id] = 1
                        it[name] = "Test Project"
                        it[slug] = "test-project"
                    }
                    IssueStatuses.insert {
                        it[issue_id] = "issue-1"
                        it[project_id] = -1
                        it[status] = "resolved"
                        it[updated_at] = kotlin.time.Clock.System.now()
                    }
                }

                val service = DashboardService.create()
                val issues = service.getIssues(projectId = -1, page = 1, limit = 10, status = "resolved")

                assertEquals(1, issues.size)
                assertEquals("issue-1", issues.first().id)
                assertEquals("resolved", issues.first().status)
                // Pagination uses overfetch: (limit+offset)*5 when status filter is set; page 1 => offset 0 => LIMIT 50
                assertTrue(queries.any { it.contains("LIMIT 50") })
            }
        }

    @Test
    fun `getIssues for organization applies organization and service clauses`() =
        runBlocking {
            val queries = Collections.synchronizedList(mutableListOf<String>())
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                queries += query
                if (query.contains("events e") && query.contains("GROUP BY issue_id")) {
                    exchange.respond(
                        200,
                        issueOrgRowJson(),
                        contentType = "text/plain"
                    )
                } else {
                    exchange.respond(200, "", contentType = "text/plain")
                }
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

                transaction {
                    Organizations.insert {
                        it[id] = 1
                        it[name] = "Test Org"
                        it[slug] = "test-org"
                    }
                    Projects.insert {
                        it[id] = 123
                        it[organization_id] = 1
                        it[name] = "API"
                        it[slug] = "api"
                    }
                }

                val service = DashboardService.create()
                val issues = service.getIssues(
                    IssueListQuery(
                        organizationId = 1,
                        page = 1,
                        limit = 10,
                        status = null,
                        serviceIds = listOf(123)
                    )
                )

                assertEquals(1, issues.size)
                assertEquals("issue-org-1", issues.first().id)
                assertTrue(queries.any { it.contains("organization_id = 1") })
                assertTrue(queries.any { it.contains("project_id IN (123)") })
            }
        }

    @Test
    fun `org service read methods return empty results for empty service scope`() =
        runBlocking {
            val service = DashboardService.create()

            val releases = service.getReleasesForServices(organizationId = 1, serviceIds = emptyList())
            val releaseStats =
                service.getReleaseStatsForServices(organizationId = 1, serviceIds = emptyList(), version = "1.0.0")
            val replays = service.getReplaysForServices(organizationId = 1, serviceIds = emptyList())
            val feedback = service.getFeedbackForServices(organizationId = 1, serviceIds = emptyList())

            assertTrue(releases.isEmpty())
            assertNull(releaseStats)
            assertTrue(replays.isEmpty())
            assertTrue(feedback.isEmpty())
        }

    @Test
    fun `getIssue returns detail with latest event and demo project name`() =
        runBlocking {
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains("FROM `test`.issues") && query.contains("WHERE issue_id = 'issue-1'") -> {
                        exchange.respond(200, """{"project_id":-1}""", contentType = "text/plain")
                    }

                    query.contains("FROM `test`.events e") && query.contains("fingerprint") -> {
                        exchange.respond(
                            200,
                            """
                        {"issue_id":"issue-1","title":"Crash","culprit":"Api.kt","level":"fatal","platform":"android","first_seen":"2026-02-01T00:00:00.000Z","last_seen":"2026-02-03T00:00:00.000Z","event_count":8,"user_count":2,"fingerprint":["NullPointerException","Api.kt"]}
                            """.trimIndent(),
                            contentType = "text/plain"
                        )
                    }

                    query.contains("FROM `test`.events") && query.contains("ORDER BY timestamp DESC") -> {
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
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

                transaction {
                    Organizations.insert {
                        it[id] = 1
                        it[name] = "Test Org"
                        it[slug] = "test-org"
                    }
                    Projects.insert {
                        it[id] = -1
                        it[organization_id] = 1
                        it[name] = "Android"
                        it[slug] = "android"
                    }
                }

                val service = DashboardService.create()
                val issue = service.getIssue("issue-1")

                assertNotNull(issue)
                assertEquals("issue-1", issue.id)
                assertEquals(-1, issue.projectId)
                assertEquals("Android", issue.projectName)
                assertEquals(2, issue.fingerprint.size)
                assertNotNull(issue.latestEvent)
                assertEquals("evt-1", issue.latestEvent.eventId)
                assertEquals("prod", issue.latestEvent.tags["env"])
                assertEquals("stack", issue.latestEvent.stackTrace)
                val traceContext = issue.latestEvent.contextsJson?.jsonObject?.get("trace")?.jsonObject
                assertEquals("t1", traceContext?.get("trace_id")?.jsonPrimitive?.contentOrNull)
            }
        }

    private fun issueOrgRowJson(): String =
        """{"issue_id":"issue-org-1","project_id":123,"title":"Org crash","culprit":"Api.kt",""" +
            """"level":"error","platform":"kotlin","first_seen":"2026-02-01T01:00:00.000Z",""" +
            """"last_seen":"2026-02-02T01:00:00.000Z","event_count":12,"user_count":4,""" +
            """"status":"unresolved"}"""

    @Test
    fun `getIssueTransactions returns transactions linked by issue error trace id`() =
        runBlocking {
            val queries = Collections.synchronizedList(mutableListOf<String>())
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                queries += query
                when {
                    query.contains("FROM `test`.issues") && query.contains("WHERE issue_id = 'issue-1'") -> {
                        exchange.respond(200, """{"project_id":-1}""", contentType = "text/plain")
                    }

                    query.contains("event_type = 'transaction'") && query.contains("issue_id = 'issue-1'") -> {
                        exchange.respond(
                            200,
                            """{"event_id":"txn-1","name":"ProjectWorkChain","op":"project.workchain",""" +
                                """"duration":1250.0,"event_ts":"2026-02-03T00:00:00.000Z",""" +
                                """"status":"internal_error","trace_id":"trace-1"}""",
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

                val service = DashboardService.create()
                val transactions = service.getIssueTransactions("issue-1", limit = 10)

                assertEquals(1, transactions.size)
                assertEquals("txn-1", transactions.first().eventId)
                assertEquals("trace-1", transactions.first().traceId)
                val traceSubquery = "SELECT DISTINCT JSONExtractString(contexts, 'trace', 'trace_id')"
                assertTrue(queries.any { it.contains(traceSubquery) })
            }
        }

    @Test
    fun `getTraceDetails assembles span waterfall timing`() =
        runBlocking {
            MockHttpServer { exchange ->
                val query = exchange.requestBodyText()
                if (query.contains("FROM `test`.apm_spans") && query.contains("trace_id_hex = 'trace-1'")) {
                    exchange.respond(
                        200,
                        """
                    {"span_id":"s1","parent_span_id":"","trace_id":"trace-1","meta":{"sentry.transaction_id":"tx-1"},"op":"http.server","description":"GET /","start_ns":"1000000000","duration_ns":"1500000000","error":0}
                    {"span_id":"s2","parent_span_id":"s1","trace_id":"trace-1","meta":{"sentry.transaction_id":"tx-1"},"op":"db","description":"SELECT","start_ns":"2600000000","duration_ns":"2400000000","error":0}
                        """.trimIndent(),
                        contentType = "text/plain"
                    )
                } else {
                    exchange.respond(200, "", contentType = "text/plain")
                }
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                transaction {
                    Organizations.insert {
                        it[id] = 1
                        it[name] = "Test Org"
                        it[slug] = "test-org"
                    }
                    Projects.insert {
                        it[id] = -1
                        it[organization_id] = 1
                        it[name] = "Test Project"
                        it[slug] = "test-project"
                    }
                }

                val service = DashboardService.create()
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

                val service = DashboardService.create()
                val projectId = service.getProjectIdForEvent(compactEventId)

                assertEquals(123L, projectId)
                assertTrue(queries.any { it.contains(normalizedEventId) })
            }
        }
}
