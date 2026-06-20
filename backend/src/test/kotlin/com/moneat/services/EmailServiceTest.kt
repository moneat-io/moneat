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
import kotlin.test.assertEquals
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

    @Test
    fun `sendBillingThresholdAlertEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendBillingThresholdAlertEmail(
            to = "owner@example.com",
            subject = "[Billing Org] Usage is over the included limit",
            data = billingInsightEmailData()
        )
    }

    @Test
    fun `sendBillingInsightsEmail does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendBillingInsightsEmail("owner@example.com", billingInsightEmailData())
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

    @Test
    fun `sendBillingInsightsEmail renders billing rows in HTML`() {
        val service = spyk(EmailService())
        val htmlSlot = slot<String>()
        every {
            service.sendEmail(
                any(), any(), capture(htmlSlot), any(), any()
            )
        } just Runs

        service.sendBillingInsightsEmail("owner@example.com", billingInsightEmailData())

        assertTrue(htmlSlot.captured.contains("GB-Billed Ingestion"))
        assertTrue(htmlSlot.captured.contains("80%"))
        assertTrue(htmlSlot.captured.contains("aria-label=\"80% used\""))
        assertTrue(htmlSlot.captured.contains("width=\"80%\""))
        assertTrue(htmlSlot.captured.contains("https://moneat.io/email/logo-mark.png"))
        assertTrue(!htmlSlot.captured.contains("<svg"))
        assertNoUnresolvedTemplateMarkers(htmlSlot.captured)
    }

    @Test
    fun `sendBillingInsightsEmail renders progress colors and bounds`() {
        val service = spyk(EmailService())
        val htmlSlot = slot<String>()
        every {
            service.sendEmail(
                any(), any(), capture(htmlSlot), any(), any()
            )
        } just Runs

        val rows =
            listOf(
                EmailService.BillingInsightRow("Overage", "13.31 GB", "10.00 GB", "133.1%", "Over limit"),
                EmailService.BillingInsightRow("Critical", "0.04 GB", "10.00 GB", "0.4%", "Critical"),
                EmailService.BillingInsightRow("Approaching", "4.52 GB", "10.00 GB", "45.2%", "Approaching"),
                EmailService.BillingInsightRow("Unlimited", "12 spans", "Unlimited", "Unlimited", "On track"),
                EmailService.BillingInsightRow("Unknown", "n/a", "10.00 GB", "not available", "On track"),
                EmailService.BillingInsightRow("Zero", "0 GB", "10.00 GB", "0%", "On track")
            )

        service.sendBillingInsightsEmail("owner@example.com", billingInsightEmailData(rows = rows))

        val html = htmlSlot.captured
        assertTrue(html.contains("background:#cf2126"))
        assertTrue(html.contains("background:#e0a100"))
        assertTrue(html.contains("background:#0369a1"))
        assertTrue(html.contains("background:#0ea5e9"))
        assertTrue(html.contains("width=\"100%\""))
        assertTrue(html.contains("width=\"45%\""))
        assertTrue(html.contains("width=\"2%\""))
        assertTrue(html.contains("width=\"0%\""))
        assertTrue(html.contains("aria-label=\"Unlimited used\""))
        assertTrue(html.contains("aria-label=\"not available used\""))
        assertNoUnresolvedTemplateMarkers(html)
    }

    @Test
    fun `sendBillingInsightsEmail renders empty usage state`() {
        val service = spyk(EmailService())
        val htmlSlot = slot<String>()
        every {
            service.sendEmail(
                any(), any(), capture(htmlSlot), any(), any()
            )
        } just Runs

        service.sendBillingInsightsEmail("owner@example.com", billingInsightEmailData(rows = emptyList()))

        assertTrue(htmlSlot.captured.contains("No billable usage yet."))
        assertNoUnresolvedTemplateMarkers(htmlSlot.captured)
    }

    @Test
    fun `redesigned transactional templates render without unresolved tokens or inert links`() {
        val service = spyk(EmailService())
        val htmlBodies = mutableListOf<String>()
        every {
            service.sendEmail(
                any(), any(), capture(htmlBodies), any(), any()
            )
        } just Runs

        service.sendVerificationEmail("user@example.com", "tok-verify", "Ada")
        service.sendPasswordResetEmail("user@example.com", "tok-reset", "Ada")
        service.sendInvitationEmail("invitee@example.com", "Bob", "Acme", "admin", "tok-invite")
        service.sendErrorAlertEmail("ops@example.com", errorAlertData())
        service.sendWeeklySummaryEmail("lead@example.com", weeklySummaryData())
        service.sendBillingThresholdAlertEmail(
            to = "owner@example.com",
            subject = "[Billing Org] Usage is over the included limit",
            data = billingInsightEmailData()
        )
        service.sendBillingInsightsEmail("owner@example.com", billingInsightEmailData())
        service.sendHostAlertEmail("sre@example.com", hostAlertData())
        service.sendMonitorAlertEmail("sre@example.com", monitorAlertData())
        service.sendResolvedAlertEmail("sre@example.com", "host_up", resolvedAlertData())
        service.sendHostDownEmail("sre@example.com", "db-1", "5 minutes ago", "https://app.example/hosts/db-1")
        service.sendHostUpEmail("sre@example.com", "db-1", "https://app.example/hosts/db-1")
        service.sendUptimeAlertEmail(
            to = "sre@example.com",
            monitorName = "API",
            status = "down",
            message = "timeout",
            monitorUrl = "https://app.example/monitors/1"
        )
        service.sendUptimeAlertEmail(
            to = "sre@example.com",
            monitorName = "API",
            status = "up",
            message = "",
            monitorUrl = "https://app.example/monitors/1"
        )

        assertEquals(14, htmlBodies.size)
        htmlBodies.forEach(::assertNoUnresolvedTemplateMarkers)
    }

    // ──── Enterprise sales inquiry ────
    @Test
    fun `sendEnterpriseSalesInquiry does not throw when SMTP not configured`() {
        val service = EmailService()
        service.sendEnterpriseSalesInquiry(
            name = "Ada Lovelace",
            email = "ada@acme.com",
            company = "Acme Corp",
            message = "We need 5TB of ingestion and a dedicated SLA."
        )
    }

    @Test
    fun `sendEnterpriseSalesInquiry routes to sales inbox with prospect reply-to and renders details`() {
        val service = spyk(EmailService())
        val toSlot = slot<String>()
        val htmlSlot = slot<String>()
        val replyToList = mutableListOf<String?>()
        every {
            service.sendEmail(
                capture(toSlot), any(), capture(htmlSlot), any(), any(), captureNullable(replyToList)
            )
        } just Runs

        service.sendEnterpriseSalesInquiry(
            name = "Ada Lovelace",
            email = "ada@acme.com",
            company = "Acme Corp",
            message = "We need 5TB of ingestion and a dedicated SLA."
        )

        assertEquals("support@moneat.io", toSlot.captured)
        assertEquals("ada@acme.com", replyToList.last())
        assertTrue(htmlSlot.captured.contains("Ada Lovelace"))
        assertTrue(htmlSlot.captured.contains("ada@acme.com"))
        assertTrue(htmlSlot.captured.contains("Acme Corp"))
        assertTrue(htmlSlot.captured.contains("We need 5TB of ingestion and a dedicated SLA."))
    }

    @Test
    fun `sendEnterpriseSalesInquiry escapes HTML in user-supplied fields`() {
        val service = spyk(EmailService())
        val htmlSlot = slot<String>()
        every {
            service.sendEmail(any(), any(), capture(htmlSlot), any(), any(), any())
        } just Runs

        service.sendEnterpriseSalesInquiry(
            name = "<script>alert(1)</script>",
            email = "evil@acme.com",
            company = "Acme & Co",
            message = "drop <table>"
        )

        assertTrue(htmlSlot.captured.contains("&lt;script&gt;"))
        assertTrue(htmlSlot.captured.contains("Acme &amp; Co"))
        assertTrue(!htmlSlot.captured.contains("<script>alert(1)</script>"))
    }

    private fun errorAlertData() =
        EmailService.ErrorAlertData(
            issueTitle = "TypeError: missing total",
            issueLevel = "error",
            issueCulprit = "CartController.computeTotals src/controllers/cart.ts:148:24",
            issueMessage = "Cannot read properties of undefined",
            issueCount = "12",
            issueUrl = "https://app.example/issues/issue-1",
            projectName = "checkout-api",
            environment = "production",
            timestamp = "2026-01-01T00:00:00Z",
            stackTrace = "TypeError\nat CartController.computeTotals",
            settingsUrl = "https://app.example/settings",
            unsubscribeUrl = "https://app.example/unsub",
            errorRate = "4.7%",
            errorRateDelta = "up 3.9pt",
            p95Latency = "842 ms",
            p95LatencyDelta = "up 210 ms",
            throughput = "1.2k/m",
            throughputDelta = "down 8%",
            usersAffected = "312",
            firstSeen = "14m ago",
            lastSeen = "8s ago",
            eventSeries = listOf(4, 5, 7, 9, 12, 18, 24),
            spikeIndex = 4,
            issueFunction = "CartController.computeTotals",
            issueLocation = "src/controllers/cart.ts:148:24",
            release = "v2.4.1",
            deploySummary = "Deployed before first event",
            contextTags =
            listOf(
                EmailService.ContextTag("runtime", "node@20"),
                EmailService.ContextTag("http.route", "POST /api/checkout")
            ),
            codeOwner = "@checkout-team",
            stackFrames =
            listOf(
                EmailService.StackFrame("TypeError: missing total", heading = true),
                EmailService.StackFrame("at CartController.computeTotals", inApp = true)
            )
        )

    private fun weeklySummaryData() =
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
            unsubscribeUrl = "https://app.example/unsub",
            organizationName = "Acme"
        )

    private fun hostAlertData() =
        EmailService.HostAlertData(
            hostName = "db-1",
            lastSeenText = "3 minutes ago",
            hostUrl = "https://app.example/hosts/db-1",
            settingsUrl = "https://app.example/settings",
            unsubscribeUrl = "https://app.example/unsub",
            currentValue = "92%",
            baselineSummary = "up from 41% baseline",
            sustainedDuration = "5m",
            processes =
            listOf(
                EmailService.HostProcessRow("postgres", "61%", "2.1 GiB"),
                EmailService.HostProcessRow("otel-collector", "6%", "412 MiB")
            ),
            historySeries = listOf(38, 40, 61, 80, 92)
        )

    private fun monitorAlertData() =
        EmailService.MonitorAlertData(
            monitorName = "API",
            status = "down",
            message = "timeout",
            monitorUrl = "https://app.example/monitors/1",
            settingsUrl = "https://app.example/settings",
            unsubscribeUrl = "https://app.example/unsub",
            metricName = "5xx error rate",
            currentValue = "15.2%",
            threshold = "10%",
            condition = "avg over 5m > 10%",
            dashboardName = "Production Overview",
            widgetName = "HTTP 5xx error rate",
            historySeries = listOf(2, 3, 5, 10, 15),
            breachIndex = 3
        )

    private fun resolvedAlertData() =
        EmailService.ResolvedAlertData(
            targetName = "db-1",
            metricName = "CPU",
            alertUrl = "https://app.example/hosts/db-1",
            settingsUrl = "https://app.example/settings",
            unsubscribeUrl = "https://app.example/unsub",
            duration = "12m",
            peakValue = "92%",
            currentValue = "38%",
            monitorName = "CPU > 90%",
            triggeredAt = "16:30 UTC",
            recoveredAt = "16:42 UTC"
        )

    private fun billingInsightEmailData(
        rows: List<EmailService.BillingInsightRow> =
            listOf(
                EmailService.BillingInsightRow(
                    label = "GB-Billed Ingestion",
                    used = "8.00 GB",
                    limit = "10.00 GB",
                    percent = "80%",
                    status = "Watch"
                )
            )
    ) =
        EmailService.BillingInsightEmailData(
            organizationName = "Billing Org",
            plan = "PRO",
            periodStart = "2026-01-01",
            periodEnd = "2026-01-31",
            headline = "Usage is approaching the included limit",
            summary = "GB-billed ingestion is projected to reach the monthly limit.",
            dashboardUrl = "https://app.example/usage-insights",
            settingsUrl = "https://app.example/settings?tab=billing",
            rows = rows,
            totalOverage = "\$0.00"
        )

    private fun assertNoUnresolvedTemplateMarkers(html: String) {
        val unresolvedToken = Regex("""\{\{\s*[\w.]+\s*}}""").find(html)?.value
        val sentinel = Regex("""[A-Z][A-Z0-9_]+_PLACEHOLDER""").find(html)?.value
        assertTrue(unresolvedToken == null, "HTML contains unresolved token: $unresolvedToken")
        assertTrue(sentinel == null, "HTML contains sentinel: $sentinel")
        assertTrue(!html.contains("href=\"#\""), "HTML contains inert href")
    }
}
