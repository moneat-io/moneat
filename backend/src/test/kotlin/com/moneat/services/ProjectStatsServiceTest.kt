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
import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.ProjectStatsService
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectStatsServiceTest {
    companion object {
        private var db: Database? = null
        private const val TEXT_PLAIN = "text/plain"
    }

    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val pricingTierService = mockk<PricingTierService>()
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var service: ProjectStatsService

    @BeforeTest
    fun setup() {
        coEvery { retentionPolicyService.getRetentionDaysForProject(any()) } returns 30
        queryHelper = DashboardQueryHelper(retentionPolicyService, pricingTierService)
        service = ProjectStatsService(queryHelper)

        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_project_stats;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            IssueStatuses,
            Organizations,
            Projects,
            Subscriptions,
            PricingTierConfigs
        )
    }

    private fun seedOrgAndProject(projectId: Long = 1L) {
        transaction {
            Organizations.insert {
                it[id] = 1
                it[name] = "Test Org"
                it[slug] = "test-org"
            }
            Projects.insert {
                it[id] = projectId
                it[organization_id] = 1
                it[name] = "Test Project"
                it[slug] = "test-project"
            }
        }
    }

    private fun mockClickHouseResponses(
        totalEvents: Long = 42,
        totalIssues: Long = 5,
        unresolvedIssues: Long = 3,
        affectedUsers: Long = 10,
        handler: ((String) -> String?)? = null
    ): (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
        val query = exchange.requestBodyText()
        val customResponse = handler?.invoke(query)
        if (customResponse != null) {
            exchange.respond(200, customResponse, TEXT_PLAIN)
        } else {
            val body = when {
                query.contains("sum(event_count) as total") &&
                    query.contains("event_project_rollup_1h") ->
                    """{"total":$totalEvents}"""
                query.contains("uniqExact(issue_id) as total") &&
                    query.contains("event_issue_rollup_1h") ->
                    """{"total":$totalIssues}"""
                query.contains("status = 'unresolved'") && query.contains("issues FINAL") ->
                    """{"total":$unresolvedIssues}"""
                query.contains("uniq(user_id) as total") ->
                    """{"total":$affectedUsers}"""
                query.contains("toStartOfInterval") && query.contains("uniq(user_id)") ->
                    """{"time":"2026-01-01T00:00:00Z","count":5}"""
                query.contains("toStartOfInterval") ->
                    """{"time":"2026-01-01T00:00:00Z","count":10}"""
                query.contains("level, sum(event_count)") ->
                    """{"level":"error","count":30}
{"level":"warning","count":12}"""
                query.contains("platform, sum(event_count)") ->
                    """{"platform":"kotlin","count":25}"""
                query.contains("browser_name, sum(event_count)") ->
                    """{"browser_name":"Chrome","count":20}"""
                query.contains("environment, sum(event_count)") ->
                    """{"environment":"production","count":35}"""
                query.contains("status, count()") && query.contains("GROUP BY status") ->
                    """{"status":"unresolved","count":3}
{"status":"resolved","count":2}"""
                query.contains("issue_id, any(title) as title") ->
                    """{"issue_id":"iss-1","title":"NullPointerException","count":15}"""
                query.contains("release as version") ->
                    """{"version":"1.0.0","timestamp":"2026-01-01T12:00:00.000Z"}"""
                query.contains("DISTINCT issue_id") && query.contains("issue_id IN") ->
                    ""
                else -> ""
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }
    }

    @Test
    fun `getProjectStats returns complete stats with all query results`() = runBlocking {
        MockHttpServer(mockClickHouseResponses()).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            val stats = service.getProjectStats(projectId = 1, period = "7d")

            assertEquals(42L, stats.totalEvents)
            assertEquals(5L, stats.totalIssues)
            assertEquals(3L, stats.unresolvedIssues)
            assertEquals(10L, stats.affectedUsers)
            assertTrue(stats.eventsTimeline.isNotEmpty())
            assertEquals("2026-01-01T00:00:00Z", stats.eventsTimeline.first().timestamp)
            assertEquals(10L, stats.eventsTimeline.first().count)
            assertEquals(30L, stats.eventsByLevel["error"])
            assertEquals(12L, stats.eventsByLevel["warning"])
            assertEquals(25L, stats.eventsByPlatform["kotlin"])
            assertEquals(20L, stats.eventsByBrowser["Chrome"])
            assertEquals(35L, stats.eventsByEnvironment["production"])
            assertEquals(3L, stats.issuesByStatus["unresolved"])
            assertEquals(2L, stats.issuesByStatus["resolved"])
            assertTrue(stats.topIssues.isNotEmpty())
            assertEquals("iss-1", stats.topIssues.first().issueId)
            assertEquals("NullPointerException", stats.topIssues.first().title)
            assertEquals(15L, stats.topIssues.first().count)
            assertTrue(stats.usersTimeline.isNotEmpty())
            assertTrue(stats.releaseMarkers.isNotEmpty())
            assertEquals("1.0.0", stats.releaseMarkers.first().version)
        }
    }

    @Test
    fun `getProjectStats returns zeros on ClickHouse error`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(500, "Internal Server Error", TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            val stats = service.getProjectStats(projectId = 1, period = "7d")

            assertEquals(0L, stats.totalEvents)
            assertEquals(0L, stats.totalIssues)
            assertEquals(0L, stats.unresolvedIssues)
            assertEquals(0L, stats.affectedUsers)
            assertTrue(stats.eventsTimeline.isEmpty())
            assertTrue(stats.eventsByLevel.isEmpty())
            assertTrue(stats.eventsByPlatform.isEmpty())
            assertTrue(stats.eventsByBrowser.isEmpty())
            assertTrue(stats.eventsByEnvironment.isEmpty())
            assertTrue(stats.issuesByStatus.isEmpty())
            assertTrue(stats.topIssues.isEmpty())
            assertTrue(stats.usersTimeline.isEmpty())
            assertTrue(stats.releaseMarkers.isEmpty())
        }
    }

    @Test
    fun `getProjectStats adjusts unresolved count with PG overrides`() = runBlocking {
        val handler = mockClickHouseResponses(
            unresolvedIssues = 5
        ) { query ->
            if (query.contains("DISTINCT issue_id") && query.contains("issue_id IN")) {
                """{"issue_id":"iss-override-1"}
{"issue_id":"iss-override-2"}"""
            } else {
                null
            }
        }
        MockHttpServer(handler).use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            transaction {
                IssueStatuses.insert {
                    it[issue_id] = "iss-override-1"
                    it[project_id] = 1L
                    it[status] = "resolved"
                    it[updated_at] = kotlin.time.Clock.System.now()
                }
                IssueStatuses.insert {
                    it[issue_id] = "iss-override-2"
                    it[project_id] = 1L
                    it[status] = "ignored"
                    it[updated_at] = kotlin.time.Clock.System.now()
                }
            }

            val stats = service.getProjectStats(projectId = 1, period = "7d")

            // Two issues overridden from unresolved → resolved/ignored, so unresolved = 5 - 2 = 3
            assertEquals(3L, stats.unresolvedIssues)
            // issuesByStatus should reflect the adjustments
            val resolvedCount = stats.issuesByStatus["resolved"] ?: 0L
            assertTrue(resolvedCount > 0, "resolved count should include PG overrides")
        }
    }

    @Test
    fun `getProjectStats with 24h period uses correct interval`() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            queries += query
            val body = when {
                query.contains("sum(event_count) as total") &&
                    query.contains("event_project_rollup_1h") ->
                    """{"total":10}"""
                query.contains("uniqExact(issue_id)") ->
                    """{"total":2}"""
                query.contains("status = 'unresolved'") ->
                    """{"total":1}"""
                query.contains("uniq(user_id) as total") ->
                    """{"total":3}"""
                query.contains("status, count()") ->
                    """{"status":"unresolved","count":1}"""
                else -> ""
            }
            exchange.respond(200, body, TEXT_PLAIN)
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")
            TransactionManager.defaultDatabase = db

            seedOrgAndProject()
            val stats = service.getProjectStats(projectId = 1, period = "24h")

            assertEquals(10L, stats.totalEvents)
            // 24h period uses INTERVAL 24 HOUR and INTERVAL 60 MINUTE
            assertTrue(queries.any { it.contains("INTERVAL 24 HOUR") })
            assertTrue(queries.any { it.contains("INTERVAL 60 MINUTE") })
        }
    }
}
