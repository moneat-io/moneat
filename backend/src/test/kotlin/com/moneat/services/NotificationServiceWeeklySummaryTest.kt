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
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.notifications.services.SlackService
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
    }

    private val emailService = mockk<EmailService>(relaxed = true)
    private val slackService = mockk<SlackService>(relaxed = true)
    private val discordService = mockk<DiscordService>(relaxed = true)

    // ──── Setup & Helpers ────
    @BeforeTest
    fun setupDatabase() {
        clearMocks(emailService, slackService, discordService)

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

    // ──── Test Cases ────
    @Test
    fun `sendWeeklySummaryForUser includes crash-free rate from sessions query`() =
        runBlocking {
            val orgId = seedOrg()
            val userId = seedUser("weeklyuser@moneat.io", "Weekly User")
            seedMembership(userId, orgId)
            seedProject(orgId, "P1")

            val sessionsBody = """{"data":[{"rate":$MOCK_CRASH_FREE_RATE}]}"""

            MockHttpServer(weeklySummaryClickHouseHandler(sessionsBody)).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService, slackService, discordService)
                try {
                    service.sendWeeklySummaryForUser(userId, "weeklyuser@moneat.io")
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

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

            MockHttpServer(weeklySummaryClickHouseHandler("", sessionsStatus = 500)).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService, slackService, discordService)
                try {
                    service.sendWeeklySummaryForUser(userId, "weeklyuser2@moneat.io")
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

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

            MockHttpServer(failHandler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(emailService, slackService, discordService)
                try {
                    service.sendWeeklySummaryForUser(userId, "weeklyskip@moneat.io")
                } finally {
                    service.shutdown()
                    ClickHouseClient.close()
                }
            }

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

            MockHttpServer(handler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(
                    emailService,
                    slackService,
                    discordService,
                )
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

            MockHttpServer(handler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(
                    emailService,
                    slackService,
                    discordService,
                )
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

            MockHttpServer(handler).use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")
                val service = NotificationService(
                    emailService,
                    slackService,
                    discordService,
                )
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

            verify(exactly = 0) {
                emailService.sendWeeklySummaryEmail(any(), any())
            }
        }
}
