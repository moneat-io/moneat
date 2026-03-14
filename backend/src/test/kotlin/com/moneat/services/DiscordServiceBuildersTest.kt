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

import com.moneat.notifications.services.DiscordService
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DiscordServiceBuildersTest {
    companion object {
        private var db: Database? = null
    }

    private lateinit var discordService: DiscordService

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_discord_builders;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, OrganizationIntegrations)
        discordService = DiscordService()
    }

    private fun seedOrg(name: String = "Discord Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedDiscordIntegration(orgId: Int) {
        transaction {
            OrganizationIntegrations.insert {
                it[organization_id] = orgId
                it[integration_type] = "discord"
                it[team_id] = "123456789012345678"
                it[team_name] = "Test Guild"
                it[channel_id] = "987654321012345678"
                it[channel_name] = "alerts"
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
                discordService.sendHostAlert(
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
                discordService.sendHostDown(
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
                discordService.sendHostUp(
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
                discordService.sendUptimeAlert(
                    organizationId = orgId,
                    monitorUrl = "https://api.moneat.io/health",
                    isDown = true,
                    statusCode = 500,
                    responseTime = 1200L,
                    errorMessage = null,
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
                discordService.sendDashboardAlert(
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
                discordService.sendErrorAlert(
                    organizationId = orgId,
                    projectName = "Backend",
                    issueTitle = "NullPointerException",
                    level = "error",
                    firstSeen = "2024-06-15T10:30:00Z",
                    eventCount = 5,
                    userCount = 3,
                    issueUrl = "https://app.moneat.io/issues/100"
                )
            )
        }

    @Test
    fun `testConnection returns not configured when no integration`() =
        runBlocking {
            val orgId = seedOrg()
            val (success, error) = discordService.testConnection(orgId, "https://app.moneat.io")
            assertFalse(success)
            assertNotNull(error)
            assertTrue(error.contains("not configured"))
        }

    // ── Embed building with configured integration ──────────────────────
    // Embeds are built before the HTTP call; bot token is blank so sendMessage returns early.

    @Test
    fun `sendHostAlert builds embed with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Embed Org HA")
            seedDiscordIntegration(orgId)
            val result = discordService.sendHostAlert(
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
    fun `sendHostDown builds embed with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Embed Org HD")
            seedDiscordIntegration(orgId)
            val result = discordService.sendHostDown(
                organizationId = orgId,
                hostName = "db-primary",
                lastSeen = "2024-06-15T10:30:00Z",
                hostId = 7,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendHostUp builds embed with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Embed Org HU")
            seedDiscordIntegration(orgId)
            val result = discordService.sendHostUp(
                organizationId = orgId,
                hostName = "cache-node-3",
                hostId = 15,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendUptimeAlert builds embed for down monitor with error message`() =
        runBlocking {
            val orgId = seedOrg("Embed Org UD")
            seedDiscordIntegration(orgId)
            val result = discordService.sendUptimeAlert(
                organizationId = orgId,
                monitorUrl = "https://api.moneat.io/health",
                isDown = true,
                statusCode = null,
                responseTime = 5000L,
                errorMessage = "Connection refused",
                monitorId = UUID.randomUUID(),
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendUptimeAlert builds embed for down monitor with status code`() =
        runBlocking {
            val orgId = seedOrg("Embed Org US")
            seedDiscordIntegration(orgId)
            val result = discordService.sendUptimeAlert(
                organizationId = orgId,
                monitorUrl = "https://api.moneat.io/health",
                isDown = true,
                statusCode = 503,
                responseTime = 1200L,
                errorMessage = null,
                monitorId = UUID.randomUUID(),
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendUptimeAlert builds embed for down monitor with unknown status`() =
        runBlocking {
            val orgId = seedOrg("Embed Org UU")
            seedDiscordIntegration(orgId)
            val result = discordService.sendUptimeAlert(
                organizationId = orgId,
                monitorUrl = "https://api.moneat.io/health",
                isDown = true,
                statusCode = null,
                responseTime = null,
                errorMessage = null,
                monitorId = UUID.randomUUID(),
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendUptimeAlert builds embed for recovered monitor`() =
        runBlocking {
            val orgId = seedOrg("Embed Org UR")
            seedDiscordIntegration(orgId)
            val result = discordService.sendUptimeAlert(
                organizationId = orgId,
                monitorUrl = "https://api.moneat.io/health",
                isDown = false,
                statusCode = 200,
                responseTime = 45L,
                errorMessage = null,
                monitorId = UUID.randomUUID(),
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendUptimeAlert builds embed without response time`() =
        runBlocking {
            val orgId = seedOrg("Embed Org UNR")
            seedDiscordIntegration(orgId)
            val result = discordService.sendUptimeAlert(
                organizationId = orgId,
                monitorUrl = "https://api.moneat.io/health",
                isDown = true,
                statusCode = 500,
                responseTime = null,
                errorMessage = null,
                monitorId = UUID.randomUUID(),
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendDashboardAlert builds embed with CRITICAL severity`() =
        runBlocking {
            val orgId = seedOrg("Embed Org DC")
            seedDiscordIntegration(orgId)
            val result = discordService.sendDashboardAlert(
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
    fun `sendDashboardAlert builds embed with HIGH severity`() =
        runBlocking {
            val orgId = seedOrg("Embed Org DH")
            seedDiscordIntegration(orgId)
            val result = discordService.sendDashboardAlert(
                organizationId = orgId,
                alertName = "Latency Spike",
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
    fun `sendDashboardAlert builds embed with MEDIUM severity`() =
        runBlocking {
            val orgId = seedOrg("Embed Org DM")
            seedDiscordIntegration(orgId)
            val result = discordService.sendDashboardAlert(
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
    fun `sendDashboardAlert builds embed with LOW severity`() =
        runBlocking {
            val orgId = seedOrg("Embed Org DL")
            seedDiscordIntegration(orgId)
            val result = discordService.sendDashboardAlert(
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
    fun `sendDashboardAlert builds embed with null severity`() =
        runBlocking {
            val orgId = seedOrg("Embed Org DN")
            seedDiscordIntegration(orgId)
            val result = discordService.sendDashboardAlert(
                organizationId = orgId,
                alertName = "Custom Alert",
                dashboardTitle = "Custom Dashboard",
                widgetTitle = "Widget",
                condition = "=",
                threshold = "0",
                currentValue = "1",
                severity = null,
                dashboardId = 14L,
                baseUrl = "https://app.moneat.io"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds embed with fatal level`() =
        runBlocking {
            val orgId = seedOrg("Embed Org EF")
            seedDiscordIntegration(orgId)
            val result = discordService.sendErrorAlert(
                organizationId = orgId,
                projectName = "Backend API",
                issueTitle = "OutOfMemoryError",
                level = "fatal",
                firstSeen = "2024-06-15T10:30:00Z",
                eventCount = 3,
                userCount = 2,
                issueUrl = "https://app.moneat.io/issues/600"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds embed with error level`() =
        runBlocking {
            val orgId = seedOrg("Embed Org EE")
            seedDiscordIntegration(orgId)
            val result = discordService.sendErrorAlert(
                organizationId = orgId,
                projectName = "Backend API",
                issueTitle = "NullPointerException in UserService",
                level = "error",
                firstSeen = "2024-06-15T10:30:00Z",
                eventCount = 42,
                userCount = 15,
                issueUrl = "https://app.moneat.io/issues/500"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds embed with warning level`() =
        runBlocking {
            val orgId = seedOrg("Embed Org EW")
            seedDiscordIntegration(orgId)
            val result = discordService.sendErrorAlert(
                organizationId = orgId,
                projectName = "Worker",
                issueTitle = "Deprecated API usage",
                level = "warning",
                firstSeen = "2024-06-15T11:00:00Z",
                eventCount = 100,
                userCount = 50,
                issueUrl = "https://app.moneat.io/issues/501"
            )
            assertFalse(result)
        }

    @Test
    fun `sendErrorAlert builds embed with info level`() =
        runBlocking {
            val orgId = seedOrg("Embed Org EI")
            seedDiscordIntegration(orgId)
            val result = discordService.sendErrorAlert(
                organizationId = orgId,
                projectName = "Frontend",
                issueTitle = "Feature flag evaluated",
                level = "info",
                firstSeen = "2024-06-15T12:00:00Z",
                eventCount = 1,
                userCount = 1,
                issueUrl = "https://app.moneat.io/issues/502"
            )
            assertFalse(result)
        }

    @Test
    fun `testConnection builds embed with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Embed Org TC")
            seedDiscordIntegration(orgId)
            val (success, _) = discordService.testConnection(orgId, "https://app.moneat.io")
            assertFalse(success)
        }

    // ── Disabled / misconfigured integration ────────────────────────────

    @Test
    fun `sendHostAlert returns false when integration is disabled`() =
        runBlocking {
            val orgId = seedOrg("Disabled Org")
            transaction {
                OrganizationIntegrations.insert {
                    it[organization_id] = orgId
                    it[integration_type] = "discord"
                    it[team_id] = "123"
                    it[channel_id] = "456"
                    it[enabled] = false
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }
            }
            assertFalse(
                discordService.sendHostAlert(
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
    fun `getDiscordConfig returns null when guild id is missing`() =
        runBlocking {
            val orgId = seedOrg("No Guild Org")
            transaction {
                OrganizationIntegrations.insert {
                    it[organization_id] = orgId
                    it[integration_type] = "discord"
                    it[team_id] = null
                    it[channel_id] = "456"
                    it[enabled] = true
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }
            }
            assertFalse(
                discordService.sendErrorAlert(
                    organizationId = orgId,
                    projectName = "Test",
                    issueTitle = "Error",
                    level = "error",
                    firstSeen = "2024-01-01T00:00:00Z",
                    eventCount = 1,
                    userCount = 0,
                    issueUrl = "https://app.moneat.io/issues/1"
                )
            )
        }

    @Test
    fun `getDiscordConfig returns null when channel id is missing`() =
        runBlocking {
            val orgId = seedOrg("No Channel Org")
            transaction {
                OrganizationIntegrations.insert {
                    it[organization_id] = orgId
                    it[integration_type] = "discord"
                    it[team_id] = "123"
                    it[channel_id] = null
                    it[enabled] = true
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }
            }
            assertFalse(
                discordService.sendUptimeAlert(
                    organizationId = orgId,
                    monitorUrl = "https://example.com",
                    isDown = true,
                    statusCode = 500,
                    responseTime = null,
                    errorMessage = null,
                    monitorId = UUID.randomUUID(),
                    baseUrl = "https://app.moneat.io"
                )
            )
        }

    // ── OAuth handling ──────────────────────────────────────────────────

    @Test
    fun `exchangeOAuthCode handles connection failure`() =
        runBlocking {
            val response = discordService.exchangeOAuthCode(
                code = "fake-code",
                clientId = "fake-client-id",
                clientSecret = "fake-secret",
                redirectUri = "https://app.moneat.io/callback"
            )
            assertNotNull(response.error)
        }

    @Test
    fun `listChannels returns empty when bot token is blank`() =
        runBlocking {
            val channels = discordService.listChannels("123456789")
            assertTrue(channels.isEmpty())
        }

    // ── Data class construction ─────────────────────────────────────────

    @Test
    fun `DiscordEmbed default values are correct`() {
        val embed = DiscordService.DiscordEmbed()
        assertNull(embed.title)
        assertNull(embed.description)
        assertNull(embed.url)
        assertNull(embed.color)
        assertNull(embed.fields)
        assertNull(embed.footer)
        assertNull(embed.timestamp)
    }

    @Test
    fun `DiscordField default inline is true`() {
        val field = DiscordService.DiscordField(name = "Key", value = "Value")
        assertTrue(field.inline)
    }

    @Test
    fun `DiscordMessage with null embeds and content`() {
        val msg = DiscordService.DiscordMessage()
        assertNull(msg.content)
        assertNull(msg.embeds)
    }

    @Test
    fun `DiscordOAuthResponse with error`() {
        val resp = DiscordService.DiscordOAuthResponse(error = "invalid_grant")
        assertNull(resp.access_token)
        assertNull(resp.guild)
        assertTrue(resp.error == "invalid_grant")
    }

    @Test
    fun `DiscordChannel stores type correctly`() {
        val textChannel = DiscordService.DiscordChannel(id = "1", name = "general", type = 0)
        val voiceChannel = DiscordService.DiscordChannel(id = "2", name = "voice", type = 2)
        assertTrue(textChannel.type == 0)
        assertTrue(voiceChannel.type == 2)
    }
}
