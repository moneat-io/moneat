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
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.models.AlertNotificationPreferences
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.sun.net.httpserver.HttpExchange
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.moneat.notifications.services.NotificationService.WeeklySummaryResult
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationServiceWeeklySummaryTest {
    // ──── Constants & Mocks ────
    companion object {
        private var db: Database? = null
        private const val TEXT_PLAIN = "text/plain"
        private const val MOCK_CRASH_FREE_RATE = 88.25
        private const val STATS_TOTAL_EVENTS = 100L
        private const val STATS_UNIQUE_ISSUES = 4L
        private const val STATS_UNIQUE_USERS = 20L
        private const val MULTI_P1_EVENTS = 75L
        private const val MULTI_P1_ISSUES = 3L
        private const val MULTI_P1_USERS = 10L
        private const val MULTI_P2_EVENTS = 25L
        private const val MULTI_P2_ISSUES = 1L
        private const val MULTI_P2_USERS = 5L
        private const val CRASH_FREE_P1 = 99.5
        private const val CRASH_FREE_P2 = 95.0
        private const val TREND_LOW = 50L
        private const val TREND_HIGH = 100L
        private const val EXPECTED_NEGATIVE_TREND = -50
        private const val EXPECTED_POSITIVE_TREND = 100
        private const val LARGE_K_EVENTS = 1500L
        private const val LARGE_M_EVENTS = 2_000_000L
        private const val DEFAULT_CRASH_FREE = 99.0
        private const val CLICKHOUSE_ERROR_STATUS = 500
    }

    private val emailService = mockk<EmailService>(relaxed = true)

    // ──── Setup & Helpers ────
    @BeforeTest
    fun setupDatabase() {
        clearMocks(emailService)

        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_weekly_summary;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Projects,
            NotificationPreferences,
            EmailsSent,
            AlertNotificationPreferences
        )
    }

    private fun seedOrg(name: String = "Weekly Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(email: String = "weekly@moneat.io", name: String = "Weekly User"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[Users.name] = name
                it[email_verified] = true
            } get Users.id
        }

    private fun seedMembership(userId: Int, orgId: Int) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }

    private fun seedProject(orgId: Int, name: String = "WeeklyProject"): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Projects.id
        }

    // ──── HTTP Handlers ────
    private fun weeklySummaryClickHouseHandler(
        sessionsJson: String,
        sessionsStatus: Int = 200
    ): (HttpExchange) -> Unit =
        { exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("any(culprit)") -> {
                    exchange.respond(
                        CLICKHOUSE_ERROR_STATUS,
                        "Code: 47. Unknown expression identifier culprit",
                        TEXT_PLAIN
                    )
                }
                query.contains("GROUP BY project_id") -> {
                    exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                }
                query.contains("countIf(errors = 0)") -> {
                    exchange.respond(sessionsStatus, sessionsJson, TEXT_PLAIN)
                }
                query.contains("any(message) as title") -> {
                    exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                }
                else -> {
                    val body =
                        """
                        {"data":[{
                            "total_events":$STATS_TOTAL_EVENTS,
                            "unique_issues":$STATS_UNIQUE_ISSUES,
                            "unique_users":$STATS_UNIQUE_USERS
                        }]}
                        """.trimIndent().replace("\n", "")
                    exchange.respond(200, body, TEXT_PLAIN)
                }
            }
        }

    // ──── Reusable Helpers ────
    private fun statsJson(
        events: Long,
        issues: Long,
        users: Long,
    ): String = """{"data":[{""" +
        """"total_events":$events,""" +
        """"unique_issues":$issues,""" +
        """"unique_users":$users""" +
        """}]}"""

    private fun statsHandler(
        currentStats: String,
        priorStats: String,
    ): (HttpExchange) -> Unit {
        var call = 0
        return { exchange ->
            val query = exchange.requestBodyText()
            val emptyData = """{"data":[]}"""
            when {
                query.contains("GROUP BY project_id") ->
                    exchange.respond(200, emptyData, TEXT_PLAIN)
                query.contains("any(culprit)") ->
                    exchange.respond(
                        CLICKHOUSE_ERROR_STATUS,
                        "Code: 47. Unknown expression identifier culprit",
                        TEXT_PLAIN
                    )
                query.contains("count() as total_events") -> {
                    call++
                    val body = if (call == 1) {
                        currentStats
                    } else {
                        priorStats
                    }
                    exchange.respond(200, body, TEXT_PLAIN)
                }
                query.contains("any(message) as title") ->
                    exchange.respond(200, emptyData, TEXT_PLAIN)
                query.contains("countIf(errors = 0)") -> {
                    val body =
                        """{"data":[{"rate":$DEFAULT_CRASH_FREE}]}"""
                    exchange.respond(200, body, TEXT_PLAIN)
                }
                else ->
                    exchange.respond(200, emptyData, TEXT_PLAIN)
            }
        }
    }

    private suspend fun <T> withMockClickHouse(
        handler: (HttpExchange) -> Unit,
        block: suspend (NotificationService) -> T,
    ): T = MockHttpServer(handler).use { server ->
        ClickHouseClient.close()
        ClickHouseClient.init(
            server.baseUrl,
            "test",
            "default",
            "",
        )
        val service = NotificationService(emailService)
        try {
            block(service)
        } finally {
            service.shutdown()
            ClickHouseClient.close()
        }
    }

    // ──── Test Cases ────
    @Test
    fun `sendWeeklySummaryForUser includes crash-free rate from sessions query`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser("weeklyuser@moneat.io", "Weekly User")
            seedMembership(userId, orgId)
            seedProject(orgId, "P1")

            val sessionsBody = """{"data":[{"rate":$MOCK_CRASH_FREE_RATE}]}"""

            val result = MockHttpServer(
                weeklySummaryClickHouseHandler(sessionsBody)
            ).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService)
                try {
                    service.sendWeeklySummaryForUser(userId, "weeklyuser@moneat.io")
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot = slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail("weeklyuser@moneat.io", capture(dataSlot))
            }
            assertEquals(1, dataSlot.captured.projects.size)
            assertEquals(
                "%.1f%%".format(MOCK_CRASH_FREE_RATE),
                dataSlot.captured.projects.single().crashFree
            )
            assertEquals(STATS_TOTAL_EVENTS.toString(), dataSlot.captured.totalEvents)
        }

    @Test
    fun `sendWeeklySummaryForUser shows N-A when crash-free query fails`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser("weeklyuser2@moneat.io", "Weekly User 2")
            seedMembership(userId, orgId)
            seedProject(orgId, "P2")

            val result = MockHttpServer(
                weeklySummaryClickHouseHandler("", sessionsStatus = 500)
            ).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService)
                try {
                    service.sendWeeklySummaryForUser(userId, "weeklyuser2@moneat.io")
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot = slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail("weeklyuser2@moneat.io", capture(dataSlot))
            }
            assertEquals("N/A", dataSlot.captured.projects.single().crashFree)
        }

    @Test
    fun `sendWeeklySummaryForUser skips email when ClickHouse stats query fails`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser("weeklyskip@moneat.io", "Skip User")
            seedMembership(userId, orgId)
            seedProject(orgId, "P3")

            val failHandler: (HttpExchange) -> Unit = { exchange ->
                exchange.respond(500, "Internal Server Error", TEXT_PLAIN)
            }

            val result = MockHttpServer(failHandler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService)
                try {
                    service.sendWeeklySummaryForUser(userId, "weeklyskip@moneat.io")
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

            assertEquals(WeeklySummaryResult.FAILED, result)
            verify(exactly = 0) {
                emailService.sendWeeklySummaryEmail(any(), any())
            }
        }

    @Test
    fun `sendWeeklySummaryForUser sets null trends when prior stats query fails`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser("nulltrend@moneat.io", "Null Trend User")
            seedMembership(userId, orgId)
            seedProject(orgId, "PT1")

            var statsCallCount = 0
            val handler: (HttpExchange) -> Unit = { exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains("GROUP BY project_id") -> {
                        exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                    }
                    query.contains("count() as total_events") -> {
                        statsCallCount++
                        if (statsCallCount == 1) {
                            val body = """{"data":[{
                                "total_events":$STATS_TOTAL_EVENTS,
                                "unique_issues":$STATS_UNIQUE_ISSUES,
                                "unique_users":$STATS_UNIQUE_USERS
                            }]}""".replace("\n", "")
                            exchange.respond(200, body, TEXT_PLAIN)
                        } else {
                            exchange.respond(
                                500,
                                "Internal Server Error",
                                TEXT_PLAIN
                            )
                        }
                    }
                    query.contains("any(message) as title") -> {
                        exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                    }
                    query.contains("countIf(errors = 0)") -> {
                        val body = """{"data":[{"rate":99.0}]}"""
                        exchange.respond(200, body, TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                    }
                }
            }

            val result = MockHttpServer(handler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService)
                try {
                    service.sendWeeklySummaryForUser(
                        userId,
                        "nulltrend@moneat.io",
                    )
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot = slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail(
                    "nulltrend@moneat.io",
                    capture(dataSlot),
                )
            }
            assertNull(dataSlot.captured.eventsTrend)
            assertNull(dataSlot.captured.issuesTrend)
            assertNull(dataSlot.captured.usersTrend)
            assertEquals(
                STATS_TOTAL_EVENTS.toString(),
                dataSlot.captured.totalEvents,
            )
        }

    @Test
    fun `sendWeeklySummaryForUser skips email when top issues query fails`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser("topfail@moneat.io", "TopFail User")
            seedMembership(userId, orgId)
            seedProject(orgId, "PT2")

            val handler: (HttpExchange) -> Unit = { exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains("any(message) as title") -> {
                        exchange.respond(
                            500,
                            "Internal Server Error",
                            TEXT_PLAIN
                        )
                    }
                    query.contains("count() as total_events") -> {
                        val body = """{"data":[{
                            "total_events":$STATS_TOTAL_EVENTS,
                            "unique_issues":$STATS_UNIQUE_ISSUES,
                            "unique_users":$STATS_UNIQUE_USERS
                        }]}""".replace("\n", "")
                        exchange.respond(200, body, TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                    }
                }
            }

            val result = MockHttpServer(handler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService)
                try {
                    service.sendWeeklySummaryForUser(
                        userId,
                        "topfail@moneat.io",
                    )
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

            assertEquals(WeeklySummaryResult.FAILED, result)
            verify(exactly = 0) {
                emailService.sendWeeklySummaryEmail(any(), any())
            }
        }

    @Test
    fun `sendWeeklySummaryForUser skips email when per-project stats query fails`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser("projfail@moneat.io", "ProjFail User")
            seedMembership(userId, orgId)
            seedProject(orgId, "PT3")

            val handler: (HttpExchange) -> Unit = { exchange ->
                val query = exchange.requestBodyText()
                when {
                    query.contains("GROUP BY project_id") -> {
                        exchange.respond(
                            500,
                            "Internal Server Error",
                            TEXT_PLAIN
                        )
                    }
                    query.contains("count() as total_events") -> {
                        val body = """{"data":[{
                            "total_events":$STATS_TOTAL_EVENTS,
                            "unique_issues":$STATS_UNIQUE_ISSUES,
                            "unique_users":$STATS_UNIQUE_USERS
                        }]}""".replace("\n", "")
                        exchange.respond(200, body, TEXT_PLAIN)
                    }
                    query.contains("any(message) as title") -> {
                        exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                    }
                    else -> {
                        exchange.respond(200, """{"data":[]}""", TEXT_PLAIN)
                    }
                }
            }

            val result = MockHttpServer(handler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService)
                try {
                    service.sendWeeklySummaryForUser(
                        userId,
                        "projfail@moneat.io",
                    )
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

            assertEquals(WeeklySummaryResult.FAILED, result)
            verify(exactly = 0) {
                emailService.sendWeeklySummaryEmail(any(), any())
            }
        }

    // ──── SKIPPED Result Tests ────
    @Test
    fun `sendWeeklySummaryForUser returns SKIPPED for user with no projects`() =
        runBlocking {
            val orgId = seedOrg("Empty Org")
            val userId = seedUser("noproj@moneat.io", "No Proj User")
            seedMembership(userId, orgId)

            val service = NotificationService(emailService)
            try {
                val result = service.sendWeeklySummaryForUser(
                    userId,
                    "noproj@moneat.io",
                )
                assertEquals(WeeklySummaryResult.SKIPPED, result)
            } finally {
                service.shutdown()
            }

            verify(exactly = 0) {
                emailService.sendWeeklySummaryEmail(any(), any())
            }
        }

    // ──── Batch sendWeeklySummary Tests ────
    @Test
    fun `sendWeeklySummary sends to all eligible users`() =
        runBlocking {
            val orgId = seedOrg("Batch Org")
            val user1Id = seedUser(
                "batch1@moneat.io",
                "Batch User 1",
            )
            val user2Id = seedUser(
                "batch2@moneat.io",
                "Batch User 2",
            )
            seedMembership(user1Id, orgId)
            seedMembership(user2Id, orgId)
            seedProject(orgId, "BatchProject")

            val sessionsBody =
                """{"data":[{"rate":$MOCK_CRASH_FREE_RATE}]}"""

            withMockClickHouse(
                weeklySummaryClickHouseHandler(sessionsBody)
            ) { service ->
                service.sendWeeklySummary()
            }

            verify(exactly = 2) {
                emailService.sendWeeklySummaryEmail(
                    any(),
                    any(),
                )
            }
        }

    // ──── Multi-Project Tests ────
    @Test
    fun `sendWeeklySummaryForUser with multiple projects shows per-project stats`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser(
                "multiproj@moneat.io",
                "Multi Project User",
            )
            seedMembership(userId, orgId)
            val p1Id = seedProject(orgId, "Frontend App")
            val p2Id = seedProject(orgId, "Backend API")

            val handler: (HttpExchange) -> Unit =
                { exchange ->
                    val query = exchange.requestBodyText()
                    when {
                        query.contains(
                            "GROUP BY project_id"
                        ) -> {
                            val body = """{"data":[""" +
                                """{"project_id":$p1Id,""" +
                                """"total_events":""" +
                                """$MULTI_P1_EVENTS,""" +
                                """"unique_issues":""" +
                                """$MULTI_P1_ISSUES,""" +
                                """"unique_users":""" +
                                """$MULTI_P1_USERS},""" +
                                """{"project_id":$p2Id,""" +
                                """"total_events":""" +
                                """$MULTI_P2_EVENTS,""" +
                                """"unique_issues":""" +
                                """$MULTI_P2_ISSUES,""" +
                                """"unique_users":""" +
                                """$MULTI_P2_USERS}""" +
                                """]}"""
                            exchange.respond(
                                200,
                                body,
                                TEXT_PLAIN,
                            )
                        }
                        query.contains(
                            "countIf(errors = 0)"
                        ) -> {
                            val isP1 = query.contains(
                                "project_id = $p1Id"
                            )
                            val rate =
                                if (isP1) {
                                    CRASH_FREE_P1
                                } else {
                                    CRASH_FREE_P2
                                }
                            exchange.respond(
                                200,
                                """{"data":[{"rate":$rate}]}""",
                                TEXT_PLAIN,
                            )
                        }
                        query.contains(
                            "any(message) as title"
                        ) -> {
                            exchange.respond(
                                200,
                                """{"data":[]}""",
                                TEXT_PLAIN,
                            )
                        }
                        else -> {
                            exchange.respond(
                                200,
                                statsJson(
                                    STATS_TOTAL_EVENTS,
                                    STATS_UNIQUE_ISSUES,
                                    STATS_UNIQUE_USERS,
                                ),
                                TEXT_PLAIN,
                            )
                        }
                    }
                }

            val result =
                withMockClickHouse(handler) { svc ->
                    svc.sendWeeklySummaryForUser(
                        userId,
                        "multiproj@moneat.io",
                    )
                }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot =
                slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail(
                    "multiproj@moneat.io",
                    capture(dataSlot),
                )
            }
            val projects = dataSlot.captured.projects
            assertEquals(2, projects.size)

            val frontend =
                projects.first { it.name == "Frontend App" }
            assertEquals(
                MULTI_P1_EVENTS.toString(),
                frontend.events,
            )
            assertEquals(
                MULTI_P1_ISSUES.toString(),
                frontend.issues,
            )
            assertEquals("99.5%", frontend.crashFree)

            val backend =
                projects.first { it.name == "Backend API" }
            assertEquals(
                MULTI_P2_EVENTS.toString(),
                backend.events,
            )
            assertEquals(
                MULTI_P2_ISSUES.toString(),
                backend.issues,
            )
            assertEquals("95.0%", backend.crashFree)
        }

    // ──── Trend Calculation Tests ────
    @Test
    fun `sendWeeklySummaryForUser calculates negative trend`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser(
                "negtrend@moneat.io",
                "Neg Trend",
            )
            seedMembership(userId, orgId)
            seedProject(orgId, "TrendP1")

            val handler = statsHandler(
                currentStats = statsJson(
                    TREND_LOW,
                    TREND_LOW,
                    TREND_LOW,
                ),
                priorStats = statsJson(
                    TREND_HIGH,
                    TREND_HIGH,
                    TREND_HIGH,
                ),
            )

            val result =
                withMockClickHouse(handler) { service ->
                    service.sendWeeklySummaryForUser(
                        userId,
                        "negtrend@moneat.io",
                    )
                }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot =
                slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail(
                    "negtrend@moneat.io",
                    capture(dataSlot),
                )
            }
            assertEquals(
                EXPECTED_NEGATIVE_TREND,
                dataSlot.captured.eventsTrend,
            )
        }

    @Test
    fun `sendWeeklySummaryForUser calculates positive trend`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser(
                "postrend@moneat.io",
                "Pos Trend",
            )
            seedMembership(userId, orgId)
            seedProject(orgId, "TrendP2")

            val handler = statsHandler(
                currentStats = statsJson(
                    TREND_HIGH,
                    TREND_HIGH,
                    TREND_HIGH,
                ),
                priorStats = statsJson(
                    TREND_LOW,
                    TREND_LOW,
                    TREND_LOW,
                ),
            )

            val result =
                withMockClickHouse(handler) { service ->
                    service.sendWeeklySummaryForUser(
                        userId,
                        "postrend@moneat.io",
                    )
                }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot =
                slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail(
                    "postrend@moneat.io",
                    capture(dataSlot),
                )
            }
            assertEquals(
                EXPECTED_POSITIVE_TREND,
                dataSlot.captured.eventsTrend,
            )
        }

    @Test
    fun `sendWeeklySummaryForUser shows zero trend for zero-to-zero`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser(
                "zerotrend@moneat.io",
                "Zero Trend",
            )
            seedMembership(userId, orgId)
            seedProject(orgId, "TrendP3")

            val zeroStats = statsJson(
                0,
                0,
                0,
            )
            val handler =
                statsHandler(zeroStats, zeroStats)

            val result =
                withMockClickHouse(handler) { service ->
                    service.sendWeeklySummaryForUser(
                        userId,
                        "zerotrend@moneat.io",
                    )
                }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot =
                slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail(
                    "zerotrend@moneat.io",
                    capture(dataSlot),
                )
            }
            assertEquals(0, dataSlot.captured.eventsTrend)
            assertEquals(0, dataSlot.captured.issuesTrend)
            assertEquals(0, dataSlot.captured.usersTrend)
        }

    // ──── Number Formatting Tests ────
    @Test
    fun `sendWeeklySummaryForUser formats thousands with K suffix`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser(
                "fmtk@moneat.io",
                "FmtK User",
            )
            seedMembership(userId, orgId)
            seedProject(orgId, "FmtKProj")

            val stats = statsJson(
                LARGE_K_EVENTS,
                STATS_UNIQUE_ISSUES,
                STATS_UNIQUE_USERS,
            )
            val handler = statsHandler(stats, stats)

            val result =
                withMockClickHouse(handler) { service ->
                    service.sendWeeklySummaryForUser(
                        userId,
                        "fmtk@moneat.io",
                    )
                }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot =
                slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail(
                    "fmtk@moneat.io",
                    capture(dataSlot),
                )
            }
            assertEquals(
                "1.5K",
                dataSlot.captured.totalEvents,
            )
        }

    @Test
    fun `sendWeeklySummaryForUser formats millions with M suffix`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser(
                "fmtm@moneat.io",
                "FmtM User",
            )
            seedMembership(userId, orgId)
            seedProject(orgId, "FmtMProj")

            val stats = statsJson(
                LARGE_M_EVENTS,
                STATS_UNIQUE_ISSUES,
                STATS_UNIQUE_USERS,
            )
            val handler = statsHandler(stats, stats)

            val result =
                withMockClickHouse(handler) { service ->
                    service.sendWeeklySummaryForUser(
                        userId,
                        "fmtm@moneat.io",
                    )
                }

            assertEquals(WeeklySummaryResult.SENT, result)
            val dataSlot =
                slot<EmailService.WeeklySummaryData>()
            verify(exactly = 1) {
                emailService.sendWeeklySummaryEmail(
                    "fmtm@moneat.io",
                    capture(dataSlot),
                )
            }
            assertEquals(
                "2.0M",
                dataSlot.captured.totalEvents,
            )
        }
}
