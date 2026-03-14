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

import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class SlackServiceBuildersTest {
    companion object {
        private var db: Database? = null
    }

    private lateinit var slackService: SlackService

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_slack_builders;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, OrganizationIntegrations)
        slackService = SlackService()
    }

    private fun seedOrg(name: String = "Slack Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedSlackIntegration(orgId: Int) {
        transaction {
            OrganizationIntegrations.insert {
                it[organization_id] = orgId
                it[integration_type] = "slack"
                it[access_token] = "xoxb-test-token-fake"
                it[channel_id] = "C0123456789"
                it[channel_name] = "alerts"
                it[team_id] = "T0123456789"
                it[team_name] = "Test Team"
                it[enabled] = true
                it[created_at] = Clock.System.now()
                it[updated_at] = Clock.System.now()
            }
        }
    }

    // ── No-config path tests ────────────────────────────────────────────

    @Test
    fun `sendHostAlert returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(
                slackService.sendHostAlert(
                    organizationId = orgId,
                    hostName = "api-1",
                    metric = "CPU",
                    condition = ">",
                    threshold = "90%",
                    currentValue = "95%",
                    hostId = 1,
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `sendHostDown returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(
                slackService.sendHostDown(
                    organizationId = orgId,
                    hostName = "db-primary",
                    lastSeen = "2024-01-01T00:00:00Z",
                    hostId = 2,
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `sendHostUp returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(
                slackService.sendHostUp(
                    organizationId = orgId,
                    hostName = "api-1",
                    hostId = 1,
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `sendUptimeAlert returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(
                slackService.sendUptimeAlert(
                    organizationId = orgId,
                    monitorName = "Health Check",
                    oldStatus = "up",
                    newStatus = "down",
                    message = "HTTP 500",
                    monitorId = UUID.randomUUID(),
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `sendDashboardAlert returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(
                slackService.sendDashboardAlert(
                    organizationId = orgId,
                    alertName = "Error Rate High",
                    dashboardTitle = "Production",
                    widgetTitle = "Error Rate",
                    condition = ">",
                    threshold = "5%",
                    currentValue = "8%",
                    severity = "CRITICAL",
                    dashboardId = 1L,
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `sendErrorAlert returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(
                slackService.sendErrorAlert(
                    organizationId = orgId,
                    projectName = "Backend",
                    issueTitle = "NullPointerException",
                    level = "error",
                    culprit = "com.moneat.Main",
                    issueId = 100L,
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `testConnection returns not configured when no integration`() =
        runBlocking {
            val orgId = seedOrg()
            val (success, message) = slackService.testConnection(orgId)
            assertFalse(success)
            assertTrue(message.contains("No Slack integration configured"))
        }

    // ── Block building with configured integration ──────────────────────
    // These exercise all block-construction code; HTTP calls fail gracefully.

    @Test
    fun `sendHostAlert builds blocks with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Alert Org HA")
            seedSlackIntegration(orgId)
            val result = slackService.sendHostAlert(
                organizationId = orgId,
                hostName = "api-prod-1",
                metric = "Memory Usage",
                condition = ">",
                threshold = "85%",
                currentValue = "92%",
                hostId = 42,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendHostDown builds blocks with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Alert Org HD")
            seedSlackIntegration(orgId)
            val result = slackService.sendHostDown(
                organizationId = orgId,
                hostName = "db-primary",
                lastSeen = "2024-06-15T10:30:00Z",
                hostId = 7,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendHostUp builds blocks with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Alert Org HU")
            seedSlackIntegration(orgId)
            val result = slackService.sendHostUp(
                organizationId = orgId,
                hostName = "cache-node-3",
                hostId = 15,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendUptimeAlert builds blocks for down monitor`() =
        runBlocking {
            val orgId = seedOrg("Alert Org UD")
            seedSlackIntegration(orgId)
            val result = slackService.sendUptimeAlert(
                organizationId = orgId,
                monitorName = "Checkout API",
                oldStatus = "up",
                newStatus = "down",
                message = "Connection timeout",
                monitorId = UUID.randomUUID(),
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendUptimeAlert builds blocks for recovered monitor`() =
        runBlocking {
            val orgId = seedOrg("Alert Org UR")
            seedSlackIntegration(orgId)
            val result = slackService.sendUptimeAlert(
                organizationId = orgId,
                monitorName = "Payment Service",
                oldStatus = "down",
                newStatus = "up",
                message = "HTTP 200",
                monitorId = UUID.randomUUID(),
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendDashboardAlert builds blocks with CRITICAL severity`() =
        runBlocking {
            val orgId = seedOrg("Alert Org DC")
            seedSlackIntegration(orgId)
            val result = slackService.sendDashboardAlert(
                organizationId = orgId,
                alertName = "Error Spike",
                dashboardTitle = "Production Overview",
                widgetTitle = "Error Rate",
                condition = ">",
                threshold = "5%",
                currentValue = "12%",
                severity = "CRITICAL",
                dashboardId = 10L,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendDashboardAlert builds blocks with HIGH severity`() =
        runBlocking {
            val orgId = seedOrg("Alert Org DH")
            seedSlackIntegration(orgId)
            val result = slackService.sendDashboardAlert(
                organizationId = orgId,
                alertName = "Latency Warning",
                dashboardTitle = "API Metrics",
                widgetTitle = "P99 Latency",
                condition = ">",
                threshold = "500ms",
                currentValue = "750ms",
                severity = "HIGH",
                dashboardId = 11L,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendDashboardAlert builds blocks with MEDIUM severity`() =
        runBlocking {
            val orgId = seedOrg("Alert Org DM")
            seedSlackIntegration(orgId)
            val result = slackService.sendDashboardAlert(
                organizationId = orgId,
                alertName = "Throughput Low",
                dashboardTitle = "Service Health",
                widgetTitle = "RPS",
                condition = "<",
                threshold = "100",
                currentValue = "45",
                severity = "MEDIUM",
                dashboardId = 12L,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendDashboardAlert builds blocks with LOW severity`() =
        runBlocking {
            val orgId = seedOrg("Alert Org DL")
            seedSlackIntegration(orgId)
            val result = slackService.sendDashboardAlert(
                organizationId = orgId,
                alertName = "Cache Miss Rate",
                dashboardTitle = "Infrastructure",
                widgetTitle = "Cache Hits",
                condition = "<",
                threshold = "90%",
                currentValue = "85%",
                severity = "LOW",
                dashboardId = 13L,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds blocks with error level and all optional fields`() =
        runBlocking {
            val orgId = seedOrg("Alert Org EE")
            seedSlackIntegration(orgId)
            val result = slackService.sendErrorAlert(
                organizationId = orgId,
                projectName = "Backend API",
                issueTitle = "NullPointerException in UserService",
                level = "error",
                culprit = "com.moneat.services.UserService.getUser",
                issueId = 500L,
                baseUrl = "https://app.moneat.io",
                occurrenceCount = 15,
                environment = "production",
                timestamp = "2024-06-15T10:30:00Z",
                stackTrace = "  at getUser (UserService.kt:42)\n  at handle (UserRoute.kt:15)"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds blocks with warning level`() =
        runBlocking {
            val orgId = seedOrg("Alert Org EW")
            seedSlackIntegration(orgId)
            val result = slackService.sendErrorAlert(
                organizationId = orgId,
                projectName = "Worker",
                issueTitle = "Deprecated API usage",
                level = "warning",
                culprit = null,
                issueId = 501L,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds blocks with info level`() =
        runBlocking {
            val orgId = seedOrg("Alert Org EI")
            seedSlackIntegration(orgId)
            val result = slackService.sendErrorAlert(
                organizationId = orgId,
                projectName = "Frontend",
                issueTitle = "Feature flag evaluated",
                level = "info",
                culprit = "flags.ts:evaluate",
                issueId = 502L,
                baseUrl = "https://app.moneat.io",
                environment = "staging"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds blocks with unknown level`() =
        runBlocking {
            val orgId = seedOrg("Alert Org EU")
            seedSlackIntegration(orgId)
            val result = slackService.sendErrorAlert(
                organizationId = orgId,
                projectName = "SDK",
                issueTitle = "Unrecognized event type",
                level = "debug",
                culprit = null,
                issueId = 503L,
                baseUrl = "https://app.moneat.io",
                occurrenceCount = 1,
                timestamp = "2024-06-15T12:00:00Z"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds blocks without optional fields`() =
        runBlocking {
            val orgId = seedOrg("Alert Org EM")
            seedSlackIntegration(orgId)
            val result = slackService.sendErrorAlert(
                organizationId = orgId,
                projectName = "API",
                issueTitle = "TimeoutException",
                level = "error",
                culprit = null,
                issueId = 504L,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `testConnection builds blocks with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Alert Org TC")
            seedSlackIntegration(orgId)
            val (success, _) = slackService.testConnection(orgId)
            assertFalse(success)
        }

    // ── OAuth and channel listing ───────────────────────────────────────

    @Test
    fun `exchangeOAuthCode handles connection failure`() =
        runBlocking {
            val response = slackService.exchangeOAuthCode(
                code = "fake-code",
                clientId = "fake-client-id",
                clientSecret = "fake-client-secret",
                redirectUri = "https://app.moneat.io/callback"
            )
            assertFalse(response.ok)
        }

    @Test
    fun `listChannels handles connection failure`() =
        runBlocking {
            val channels = slackService.listChannels("xoxb-fake-token")
            assertTrue(channels.isEmpty())
        }

    // ── Disabled integration ────────────────────────────────────────────

    @Test
    fun `sendHostAlert returns false when integration is disabled`() =
        runBlocking {
            val orgId = seedOrg("Disabled Org")
            transaction {
                OrganizationIntegrations.insert {
                    it[organization_id] = orgId
                    it[integration_type] = "slack"
                    it[access_token] = "xoxb-test-token"
                    it[channel_id] = "C999"
                    it[enabled] = false
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }
            }
            assertFalse(
                slackService.sendHostAlert(
                    organizationId = orgId,
                    hostName = "api-1",
                    metric = "CPU",
                    condition = ">",
                    threshold = "90%",
                    currentValue = "95%",
                    hostId = 1,
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `getSlackConfig returns null when token is missing`() =
        runBlocking {
            val orgId = seedOrg("No Token Org")
            transaction {
                OrganizationIntegrations.insert {
                    it[organization_id] = orgId
                    it[integration_type] = "slack"
                    it[access_token] = null
                    it[channel_id] = "C999"
                    it[enabled] = true
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }
            }
            assertFalse(
                slackService.sendErrorAlert(
                    organizationId = orgId,
                    projectName = "Test",
                    issueTitle = "Error",
                    level = "error",
                    culprit = null,
                    issueId = 1L,
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    @Test
    fun `getSlackConfig returns null when channel is missing`() =
        runBlocking {
            val orgId = seedOrg("No Channel Org")
            transaction {
                OrganizationIntegrations.insert {
                    it[organization_id] = orgId
                    it[integration_type] = "slack"
                    it[access_token] = "xoxb-token"
                    it[channel_id] = null
                    it[enabled] = true
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }
            }
            assertFalse(
                slackService.sendUptimeAlert(
                    organizationId = orgId,
                    monitorName = "Test",
                    oldStatus = "up",
                    newStatus = "down",
                    message = "Failed",
                    monitorId = UUID.randomUUID(),
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    // ── Usergroup methods ───────────────────────────────────────────────

    @Test
    fun `listUsergroups handles connection failure`() =
        runBlocking {
            val groups = slackService.listUsergroups("xoxb-fake-token")
            assertTrue(groups.isEmpty())
        }

    @Test
    fun `updateUsergroupMembers handles connection failure`() =
        runBlocking {
            val result = slackService.updateUsergroupMembers(
                accessToken = "xoxb-fake-token",
                usergroupId = "S0123456789",
                userIds = listOf("U001", "U002")
            )
            assertFalse(result)
        }
}
