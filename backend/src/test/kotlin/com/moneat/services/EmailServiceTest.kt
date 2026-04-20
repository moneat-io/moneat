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

import com.moneat.notifications.services.EmailService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import io.mockk.spyk
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmailServiceTest {

    @Test
    fun `EmailService loads application conf and constructs`() {
        val service = EmailService()
        assertNotNull(service)
    }

    @Test
    fun `sendEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendEmail(
            to = "hello@example.com",
            subject = "Test",
            htmlBody = "<p>Hi</p>",
            textBody = "Hi",
            emailType = "other"
        )
    }

    @Test
    fun `sendVerificationEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendVerificationEmail(
            email = "user@example.com",
            token = "tok-verify",
            userName = "Ada"
        )
    }

    @Test
    fun `sendPasswordResetEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendPasswordResetEmail(
            email = "user@example.com",
            token = "tok-reset",
            userName = null
        )
    }

    @Test
    fun `sendInvitationEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendInvitationEmail(
            toEmail = "invitee@example.com",
            inviterName = "Bob",
            orgName = "Acme",
            role = "member",
            token = "tok-invite"
        )
    }

    @Test
    fun `sendErrorAlertEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        val data =
            EmailService.ErrorAlertData(
                issueTitle = "NullPointerException",
                issueLevel = "error",
                issueCulprit = "com.example.Foo.bar",
                issueMessage = "NPE",
                issueCount = "12",
                issueUrl = "https://app.example/issue/1",
                projectName = "Mobile",
                environment = "prod",
                timestamp = "2026-01-01T00:00:00Z",
                stackTrace = "at Foo.bar",
                settingsUrl = "https://app.example/settings",
                unsubscribeUrl = "https://app.example/unsub"
            )
        service.sendErrorAlertEmail("ops@example.com", data)
    }

    @Test
    fun `sendWeeklySummaryEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        val data =
            EmailService.WeeklySummaryData(
                startDate = "2026-01-01",
                endDate = "2026-01-07",
                totalEvents = "1,234",
                eventsTrend = 5,
                newIssues = "42",
                issuesTrend = -3,
                affectedUsers = "100",
                usersTrend = 0,
                topIssues =
                listOf(
                    EmailService.TopIssue(
                        title = "Slow query",
                        culprit = "db",
                        project = "api",
                        count = "9"
                    )
                ),
                projects =
                listOf(
                    EmailService.ProjectSummary(
                        name = "api",
                        events = "500",
                        issues = "10",
                        crashFree = "99.1%"
                    )
                ),
                dashboardUrl = "https://app.example/dashboard",
                settingsUrl = "https://app.example/settings",
                unsubscribeUrl = "https://app.example/unsub"
            )
        service.sendWeeklySummaryEmail("lead@example.com", data)
    }

    @Test
    fun `sendHostDownEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendHostDownEmail(
            to = "sre@example.com",
            hostName = "db-1",
            lastSeenText = "5 minutes ago",
            hostUrl = "https://app.example/hosts/db-1"
        )
    }

    @Test
    fun `sendHostUpEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendHostUpEmail(
            to = "sre@example.com",
            hostName = "db-1",
            hostUrl = "https://app.example/hosts/db-1"
        )
    }

    @Test
    fun `sendUptimeAlertEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendUptimeAlertEmail(
            to = "sre@example.com",
            monitorName = "API",
            status = "down",
            message = "timeout",
            monitorUrl = "https://app.example/monitors/1"
        )
    }

    @Test
    fun `sendUptimeAlertEmail recovered branch does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendUptimeAlertEmail(
            to = "sre@example.com",
            monitorName = "API",
            status = "up",
            message = "",
            monitorUrl = "https://app.example/monitors/1"
        )
    }

    @Test
    fun `sendAccountDeletionConfirmation does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendAccountDeletionConfirmation("former@example.com")
    }

    @Test
    fun `sendOrganizationDeletionNotification does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendOrganizationDeletionNotification(
            email = "member@example.com",
            organizationName = "Old Org"
        )
    }

    @Test
    fun `sendWeeklySummaryEmail handles null trends without throwing`() {
        val service = EmailService()
        val data =
            EmailService.WeeklySummaryData(
                startDate = "2026-04-06",
                endDate = "2026-04-13",
                totalEvents = "500",
                eventsTrend = null,
                newIssues = "10",
                issuesTrend = null,
                affectedUsers = "50",
                usersTrend = null,
                topIssues = emptyList(),
                projects = emptyList(),
                dashboardUrl = "https://app.example/dashboard",
                settingsUrl = "https://app.example/settings",
                unsubscribeUrl = "https://app.example/unsub"
            )
        service.sendWeeklySummaryEmail("lead@example.com", data)
    }

    @Test
    fun `sendWeeklySummaryEmail handles mix of null and non-null trends`() {
        val service = EmailService()
        val data =
            EmailService.WeeklySummaryData(
                startDate = "2026-04-06",
                endDate = "2026-04-13",
                totalEvents = "200",
                eventsTrend = 15,
                newIssues = "3",
                issuesTrend = null,
                affectedUsers = "10",
                usersTrend = -5,
                topIssues = emptyList(),
                projects = emptyList(),
                dashboardUrl = "https://app.example/dashboard",
                settingsUrl = "https://app.example/settings",
                unsubscribeUrl = "https://app.example/unsub"
            )
        service.sendWeeklySummaryEmail("lead@example.com", data)
    }

    // ──── HTML Rendering Tests ────
    @Test
    fun `sendWeeklySummaryEmail renders mdash badge for null trends in HTML`() {
        val service = spyk(EmailService())
        val htmlSlot = slot<String>()
        every {
            service.sendEmail(
                any(), any(), capture(htmlSlot), any(), any()
            )
        } just Runs

        val data =
            EmailService.WeeklySummaryData(
                startDate = "2026-04-06",
                endDate = "2026-04-13",
                totalEvents = "500",
                eventsTrend = null,
                newIssues = "10",
                issuesTrend = null,
                affectedUsers = "50",
                usersTrend = null,
                topIssues = emptyList(),
                projects = emptyList(),
                dashboardUrl = "https://app.example/dashboard",
                settingsUrl = "https://app.example/settings",
                unsubscribeUrl = "https://app.example/unsub"
            )
        service.sendWeeklySummaryEmail("lead@example.com", data)

        assertTrue(
            htmlSlot.captured.contains("&mdash;"),
            "HTML should contain mdash entity for null trends"
        )
    }
}
