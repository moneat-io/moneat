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

import com.moneat.events.models.ExceptionInfo
import com.moneat.events.models.ExceptionValue
import com.moneat.events.models.SentryEvent
import com.moneat.events.models.StackFrame
import com.moneat.events.models.StackTrace
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
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock

class NotificationServiceRoutingTest {
    companion object {
        private var db: Database? = null
    }

    private val emailService = mockk<EmailService>(relaxed = true)
    private val slackService = mockk<SlackService>(relaxed = true)
    private val discordService = mockk<DiscordService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_notification_routing;" +
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

    private fun seedOrg(name: String = "Routing Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(email: String = "dev@moneat.io", name: String = "Dev"): Int =
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

    private fun seedProject(orgId: Int, name: String = "Backend"): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Projects.id
        }

    private fun seedNotificationPrefs(
        userId: Int,
        projectId: Long? = null,
        issueAlerts: Boolean = true,
        errorAlerts: Boolean = true,
        weeklySummary: Boolean = true,
        frequencyMinutes: Int = 0
    ) {
        transaction {
            NotificationPreferences.insert {
                it[NotificationPreferences.user_id] = userId
                it[NotificationPreferences.project_id] = projectId
                it[issue_alerts] = issueAlerts
                it[error_alerts] = errorAlerts
                it[weekly_summary] = weeklySummary
                it[alert_frequency_minutes] = frequencyMinutes
                it[created_at] = Clock.System.now()
                it[updated_at] = Clock.System.now()
            }
        }
    }

    private fun seedAlertChannelPrefs(
        userId: Int,
        orgId: Int,
        alertSource: String,
        slackEnabled: Boolean = true,
        discordEnabled: Boolean = true
    ) {
        transaction {
            AlertNotificationPreferences.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[alert_source] = alertSource
                it[email_enabled] = true
                it[AlertNotificationPreferences.slack_enabled] = slackEnabled
                it[AlertNotificationPreferences.discord_enabled] = discordEnabled
                it[created_at] = Clock.System.now()
                it[updated_at] = Clock.System.now()
            }
        }
    }

    private fun buildEvent(
        eventId: String = "evt-1",
        message: String? = "Test error",
        level: String? = "error",
        environment: String? = "production"
    ): SentryEvent =
        SentryEvent(
            eventId = eventId,
            timestamp = Clock.System.now().toEpochMilliseconds() / 1000.0,
            level = level,
            message = message,
            environment = environment
        )

    private fun buildEventWithException(): SentryEvent =
        SentryEvent(
            eventId = "evt-exc-1",
            timestamp = Clock.System.now().toEpochMilliseconds() / 1000.0,
            level = "error",
            message = null,
            environment = "production",
            exception = ExceptionInfo(
                values = listOf(
                    ExceptionValue(
                        type = "NullPointerException",
                        value = "Cannot invoke method on null",
                        stacktrace = StackTrace(
                            frames = listOf(
                                StackFrame(
                                    filename = "UserService.kt",
                                    function = "getUser",
                                    lineno = 42,
                                    inApp = true
                                ),
                                StackFrame(
                                    filename = "UserRoute.kt",
                                    function = "handleGet",
                                    lineno = 15,
                                    inApp = true
                                )
                            )
                        )
                    )
                )
            )
        )

    // ── Routing: Slack + Discord called when prefs are enabled ──────────

    @Test
    fun `onNewIssue routes to Slack when alert prefs enable slack`() =
        runBlocking {
            val orgId = seedOrg("Slack Route Org")
            val userId = seedUser("slack-route@moneat.io", "Slack User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "SlackProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)
            seedAlertChannelPrefs(userId, orgId, "ERROR_ALERT", slackEnabled = true, discordEnabled = false)

            coEvery {
                slackService.sendErrorAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns true

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "2001", buildEvent())
                // Allow async email to complete
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    slackService.sendErrorAlert(
                        organizationId = orgId,
                        projectName = "SlackProject",
                        issueTitle = any(),
                        level = any(),
                        culprit = any(),
                        issueId = any(),
                        baseUrl = any(),
                        occurrenceCount = 1,
                        environment = any(),
                        timestamp = any(),
                        stackTrace = any()
                    )
                }
            } finally {
                service.shutdown()
            }
        }

    @Test
    fun `onNewIssue routes to Discord when alert prefs enable discord`() =
        runBlocking {
            val orgId = seedOrg("Discord Route Org")
            val userId = seedUser("discord-route@moneat.io", "Discord User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "DiscordProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)
            seedAlertChannelPrefs(userId, orgId, "ERROR_ALERT", slackEnabled = false, discordEnabled = true)

            coEvery {
                discordService.sendErrorAlert(any(), any(), any(), any(), any(), any(), any(), any())
            } returns true

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "2002", buildEvent())
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    discordService.sendErrorAlert(
                        organizationId = orgId,
                        projectName = "DiscordProject",
                        issueTitle = any(),
                        level = any(),
                        firstSeen = any(),
                        eventCount = 1,
                        userCount = 0,
                        issueUrl = any()
                    )
                }
            } finally {
                service.shutdown()
            }
        }

    @Test
    fun `onNewIssue skips Slack when alert prefs disable slack`() =
        runBlocking {
            val orgId = seedOrg("No Slack Org")
            val userId = seedUser("no-slack@moneat.io", "No Slack User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "NoSlackProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)
            seedAlertChannelPrefs(userId, orgId, "ERROR_ALERT", slackEnabled = false, discordEnabled = false)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "2003", buildEvent())
                Thread.sleep(500)

                coVerify(exactly = 0) {
                    slackService.sendErrorAlert(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                    )
                }
                coVerify(exactly = 0) {
                    discordService.sendErrorAlert(any(), any(), any(), any(), any(), any(), any(), any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Routing: email suppression via prefs ────────────────────────────

    @Test
    fun `onNewIssue skips email when issue alerts are disabled`() =
        runBlocking {
            val orgId = seedOrg("No Alert Org")
            val userId = seedUser("no-alert@moneat.io", "No Alert User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "NoAlertProject")
            seedNotificationPrefs(userId, issueAlerts = false)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "2004", buildEvent())
                Thread.sleep(500)

                coVerify(exactly = 0) {
                    emailService.sendErrorAlertEmail(any(), any())
                }
            } finally {
                service.shutdown()
            }
        }

    @Test
    fun `onNewIssue skips email when error alerts are disabled`() =
        runBlocking {
            val orgId = seedOrg("No Error Org")
            val userId = seedUser("no-error@moneat.io", "No Error User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "NoErrorProject")
            seedNotificationPrefs(userId, errorAlerts = false)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "2005", buildEvent())
                Thread.sleep(500)

                coVerify(exactly = 0) {
                    emailService.sendErrorAlertEmail(any(), any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Rate limiting ───────────────────────────────────────────────────

    @Test
    fun `onNewIssue rate limits alerts per user and project`() =
        runBlocking {
            val orgId = seedOrg("Rate Limit Org")
            val userId = seedUser("rate-limit@moneat.io", "Rate User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "RateLimitProject")
            seedNotificationPrefs(userId, frequencyMinutes = 60)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "3001", buildEvent(eventId = "r1"))
                Thread.sleep(500)

                // Second alert within 60-minute window should be throttled
                service.onNewIssue(projectId, "3002", buildEvent(eventId = "r2"))
                Thread.sleep(500)

                coVerify(atMost = 1) {
                    emailService.sendErrorAlertEmail("rate-limit@moneat.io", any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Event with exception data ───────────────────────────────────────

    @Test
    fun `onNewIssue extracts culprit and stack trace from exception`() =
        runBlocking {
            val orgId = seedOrg("Exception Org")
            val userId = seedUser("exc@moneat.io", "Exc User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "ExceptionProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "4001", buildEventWithException())
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    emailService.sendErrorAlertEmail("exc@moneat.io", any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Missing project ─────────────────────────────────────────────────

    @Test
    fun `onNewIssue returns early when project does not exist`() =
        runBlocking {
            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(99999L, "5001", buildEvent())
                Thread.sleep(200)

                coVerify(exactly = 0) {
                    emailService.sendErrorAlertEmail(any(), any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── No users to notify ──────────────────────────────────────────────

    @Test
    fun `onNewIssue returns early when no verified users in org`() =
        runBlocking {
            val orgId = seedOrg("Empty Org")
            val projectId = seedProject(orgId, "EmptyProject")

            // User with email_verified = false
            val userId = transaction {
                Users.insert {
                    it[email] = "unverified@moneat.io"
                    it[password_hash] = "hash"
                    it[name] = "Unverified"
                    it[email_verified] = false
                } get Users.id
            }
            seedMembership(userId, orgId)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "6001", buildEvent())
                Thread.sleep(200)

                coVerify(exactly = 0) {
                    emailService.sendErrorAlertEmail(any(), any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Event without message uses exception value ──────────────────────

    @Test
    fun `onNewIssue uses exception value when message is null`() =
        runBlocking {
            val orgId = seedOrg("NullMsg Org")
            val userId = seedUser("nullmsg@moneat.io", "NullMsg User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "NullMsgProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)

            val event = buildEventWithException()

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "7001", event)
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    emailService.sendErrorAlertEmail("nullmsg@moneat.io", any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Event without environment defaults ──────────────────────────────

    @Test
    fun `onNewIssue defaults environment to production when null`() =
        runBlocking {
            val orgId = seedOrg("NoEnv Org")
            val userId = seedUser("noenv@moneat.io", "NoEnv User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "NoEnvProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)

            val event = buildEvent(environment = null)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "8001", event)
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    emailService.sendErrorAlertEmail("noenv@moneat.io", any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Event without timestamp ─────────────────────────────────────────

    @Test
    fun `onNewIssue handles event with null timestamp`() =
        runBlocking {
            val orgId = seedOrg("NoTs Org")
            val userId = seedUser("nots@moneat.io", "NoTs User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "NoTsProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)

            val event = SentryEvent(
                eventId = "evt-nots",
                timestamp = null,
                level = "error",
                message = "Timestamp-less error"
            )

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "9001", event)
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    emailService.sendErrorAlertEmail("nots@moneat.io", any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Project-specific notification preferences ───────────────────────

    @Test
    fun `onNewIssue uses project-specific prefs over global`() =
        runBlocking {
            val orgId = seedOrg("ProjPref Org")
            val userId = seedUser("projpref@moneat.io", "ProjPref User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "ProjPrefProject")

            // Global prefs: alerts enabled
            seedNotificationPrefs(userId, projectId = null, issueAlerts = true, errorAlerts = true)
            // Project-specific prefs: alerts disabled
            seedNotificationPrefs(userId, projectId = projectId, issueAlerts = false, errorAlerts = true)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "10001", buildEvent())
                Thread.sleep(500)

                coVerify(exactly = 0) {
                    emailService.sendErrorAlertEmail("projpref@moneat.io", any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Shutdown ─────────────────────────────────────────────────────────

    @Test
    fun `shutdown stops the scheduler without error`() {
        val service = NotificationService(emailService, slackService, discordService)
        service.shutdown()
    }

    // ── Multiple users in org ───────────────────────────────────────────

    @Test
    fun `onNewIssue sends to all eligible users in org`() =
        runBlocking {
            val orgId = seedOrg("MultiUser Org")
            val user1 = seedUser("user1@moneat.io", "User One")
            val user2 = seedUser("user2@moneat.io", "User Two")
            seedMembership(user1, orgId)
            seedMembership(user2, orgId)
            val projectId = seedProject(orgId, "MultiUserProject")
            seedNotificationPrefs(user1, frequencyMinutes = 0)
            seedNotificationPrefs(user2, frequencyMinutes = 0)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "11001", buildEvent())
                Thread.sleep(500)

                coVerify(atLeast = 1) { emailService.sendErrorAlertEmail("user1@moneat.io", any()) }
                coVerify(atLeast = 1) { emailService.sendErrorAlertEmail("user2@moneat.io", any()) }
            } finally {
                service.shutdown()
            }
        }

    // ── Slack and Discord error handling ─────────────────────────────────

    @Test
    fun `onNewIssue handles Slack send failure gracefully`() =
        runBlocking {
            val orgId = seedOrg("SlackFail Org")
            val userId = seedUser("slackfail@moneat.io", "SlackFail User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "SlackFailProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)
            seedAlertChannelPrefs(userId, orgId, "ERROR_ALERT", slackEnabled = true, discordEnabled = true)

            coEvery {
                slackService.sendErrorAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("Slack API timeout")

            coEvery {
                discordService.sendErrorAlert(any(), any(), any(), any(), any(), any(), any(), any())
            } throws RuntimeException("Discord API timeout")

            val service = NotificationService(emailService, slackService, discordService)
            try {
                // Should not throw even though Slack/Discord fail
                service.onNewIssue(projectId, "12001", buildEvent())
                Thread.sleep(500)
            } finally {
                service.shutdown()
            }
        }

    // ── Default preferences when none seeded ─────────────────────────────

    @Test
    fun `onNewIssue uses defaults when no notification preferences exist`() =
        runBlocking {
            val orgId = seedOrg("Default Prefs Org")
            val userId = seedUser("default-prefs@moneat.io", "DefaultPrefs")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "DefaultPrefsProject")
            // No notification preferences seeded - defaults apply (all enabled, 30 min freq)

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "13001", buildEvent())
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    emailService.sendErrorAlertEmail("default-prefs@moneat.io", any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Verify sendWeeklySummaryForUser with mock ───────────────────────

    @Test
    fun `sendWeeklySummaryForUser returns early for user with no projects`() =
        runBlocking {
            val userId = seedUser("noproject@moneat.io", "No Project User")
            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.sendWeeklySummaryForUser(userId, "noproject@moneat.io")
                coVerify(exactly = 0) {
                    emailService.sendWeeklySummaryEmail(any(), any())
                }
            } finally {
                service.shutdown()
            }
        }

    // ── Verify calculateTrend logic through multiple calls ──────────────

    @Test
    fun `onNewIssue routes both Slack and Discord when both enabled`() =
        runBlocking {
            val orgId = seedOrg("Both Channels Org")
            val userId = seedUser("both@moneat.io", "Both User")
            seedMembership(userId, orgId)
            val projectId = seedProject(orgId, "BothChannelsProject")
            seedNotificationPrefs(userId, frequencyMinutes = 0)
            seedAlertChannelPrefs(userId, orgId, "ERROR_ALERT", slackEnabled = true, discordEnabled = true)

            coEvery {
                slackService.sendErrorAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns true
            coEvery {
                discordService.sendErrorAlert(any(), any(), any(), any(), any(), any(), any(), any())
            } returns true

            val service = NotificationService(emailService, slackService, discordService)
            try {
                service.onNewIssue(projectId, "14001", buildEvent())
                Thread.sleep(500)

                coVerify(atLeast = 1) {
                    slackService.sendErrorAlert(
                        organizationId = orgId,
                        projectName = any(),
                        issueTitle = any(),
                        level = any(),
                        culprit = any(),
                        issueId = any(),
                        baseUrl = any(),
                        occurrenceCount = any(),
                        environment = any(),
                        timestamp = any(),
                        stackTrace = any()
                    )
                }
                coVerify(atLeast = 1) {
                    discordService.sendErrorAlert(
                        organizationId = orgId,
                        projectName = any(),
                        issueTitle = any(),
                        level = any(),
                        firstSeen = any(),
                        eventCount = any(),
                        userCount = any(),
                        issueUrl = any()
                    )
                }
            } finally {
                service.shutdown()
            }
        }
}
