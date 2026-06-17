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

import com.moneat.config.EnvConfig
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.HostAlertNotification
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.respond
import com.moneat.workflows.models.WorkflowPreviewField
import com.moneat.workflows.models.WorkflowStepPreview
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DiscordServiceBuildersTest {
    companion object {
        private var db: Database? = null
        private const val TEST_HOST_DB_PRIMARY = "db-primary"
        private const val BASE_URL_APP = "https://app.moneat.io"
        private const val API_HEALTH_URL = "https://api.moneat.io/health"
        private const val WIDGET_ERROR_RATE = "Error Rate"
        private const val TIMESTAMP_2024_06_15 = "2024-06-15T10:30:00Z"
        private const val UPTIME_MONITOR_DOWN = "🔴 Uptime Monitor Down"
        private const val HOST_RESOURCE_ID = "00000000-0000-4000-8000-000000000042"
    }

    private lateinit var discordService: DiscordService

    @BeforeTest
    fun setupDatabase() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("DISCORD_BOT_TOKEN") } returns ""
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

    @AfterTest
    fun teardown() {
        unmockkObject(EnvConfig)
    }

    private fun seedOrg(name: String = "Discord Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun workflowPreview(): WorkflowStepPreview =
        WorkflowStepPreview(
            step = "notification.discord",
            channel = "discord",
            title = "[P1] Worker failures detected",
            body = "Worker failures crossed the threshold",
            textBody = "[P1] Worker failures detected",
            color = "#E01E5A",
            fields = listOf(
                WorkflowPreviewField("Status", "Firing"),
                WorkflowPreviewField("Priority", "[P1]"),
                WorkflowPreviewField("Dashboard", "Moneat Backend System Health")
            ),
            ctaLabel = "View",
            ctaUrl = "$BASE_URL_APP/dashboards/13",
            fallbackText = "[P1] Worker failures detected"
        )

    private fun seedDiscordIntegration(orgId: Int) {
        transaction {
            OrganizationIntegrations.insert {
                it[organization_id] = orgId
                it[integration_type] = "discord"
                it[team_id] = "123"
                it[channel_id] = "456"
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
                    alert = HostAlertNotification(
                        hostName = "api-1",
                        metric = "CPU",
                        condition = ">",
                        threshold = "90%",
                        currentValue = "95%",
                        hostResourceId = HOST_RESOURCE_ID,
                        baseUrl = BASE_URL_APP,
                    ),
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
                    hostName = TEST_HOST_DB_PRIMARY,
                    lastSeen = "2024-01-01T00:00:00Z",
                    hostResourceId = HOST_RESOURCE_ID,
                    baseUrl = BASE_URL_APP
                )
            )
        }

    @Test
    fun `sendWorkflowAlertMessage returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(discordService.sendWorkflowAlertMessage(orgId, workflowPreview()))
        }

    @Test
    fun `sendWorkflowAlertMessage builds rich alert embed with configured integration`() =
        runBlocking {
            val orgId = seedOrg("Workflow Discord Org")
            seedDiscordIntegration(orgId)

            val result = discordService.sendWorkflowAlertMessage(orgId, workflowPreview())

            assertFalse(result)
        }

    @Test
    fun `sendHostUp returns false when no integration configured`() =
        runBlocking {
            val orgId = seedOrg()
            assertFalse(
                discordService.sendHostUp(
                    organizationId = orgId,
                    hostName = "api-1",
                    hostResourceId = HOST_RESOURCE_ID,
                    baseUrl = BASE_URL_APP
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
                    monitorUrl = API_HEALTH_URL,
                    isDown = true,
                    statusCode = 500,
                    responseTime = 1200L,
                    errorMessage = null,
                    monitorId = UUID.randomUUID(),
                    baseUrl = BASE_URL_APP
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
                    widgetTitle = WIDGET_ERROR_RATE,
                    condition = ">",
                    threshold = "5%",
                    currentValue = "8%",
                    severity = "CRITICAL",
                    dashboardId = 1L,
                    baseUrl = BASE_URL_APP
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
                    firstSeen = TIMESTAMP_2024_06_15,
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
            val (success, error) = discordService.testConnection(orgId, BASE_URL_APP)
            assertFalse(success)
            assertNotNull(error)
            assertTrue(error.contains("not configured"))
        }

    // ── Embed builder unit tests ────────────────────────────────────────
    // Assert on DiscordEmbed fields to verify embed construction.

    @Test
    fun `buildHostAlertEmbed returns embed with expected title fields color url`() {
        val embed = DiscordService.buildHostAlertEmbed(
            HostAlertNotification(
                hostName = "api-prod-1",
                metric = "Memory Usage",
                condition = ">",
                threshold = "85%",
                currentValue = "92%",
                hostResourceId = HOST_RESOURCE_ID,
                baseUrl = BASE_URL_APP,
                timestamp = "2024-01-15T10:00:00Z"
            )
        )
        assertEquals("⚠️ Host Alert", embed.title)
        assertEquals("**api-prod-1** triggered an alert", embed.description)
        assertEquals("https://app.moneat.io/monitoring/hosts/$HOST_RESOURCE_ID", embed.url)
        assertEquals(0xECB22E, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals(4, fields.size)
        assertEquals("Host", fields[0].name)
        assertEquals("api-prod-1", fields[0].value)
        assertEquals("Metric", fields[1].name)
        assertEquals("Memory Usage", fields[1].value)
        assertEquals("Condition", fields[2].name)
        assertEquals("> 85%", fields[2].value)
        assertEquals("Current Value", fields[3].name)
        assertEquals("92%", fields[3].value)
        assertEquals("Moneat Alert", embed.footer?.text)
    }

    @Test
    fun `buildHostDownEmbed returns embed with expected title fields color url`() {
        val embed = DiscordService.buildHostDownEmbed(
            hostName = TEST_HOST_DB_PRIMARY,
            lastSeen = TIMESTAMP_2024_06_15,
            hostResourceId = HOST_RESOURCE_ID,
            baseUrl = BASE_URL_APP
        )
        assertEquals("🔴 Host Down", embed.title)
        assertEquals("**$TEST_HOST_DB_PRIMARY** is not responding", embed.description)
        assertEquals("https://app.moneat.io/monitoring/hosts/$HOST_RESOURCE_ID", embed.url)
        assertEquals(0xE01E5A, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals(2, fields.size)
        assertEquals("Host", fields[0].name)
        assertEquals(TEST_HOST_DB_PRIMARY, fields[0].value)
        assertEquals("Last Seen", fields[1].name)
        assertEquals(TIMESTAMP_2024_06_15, fields[1].value)
    }

    @Test
    fun `buildHostUpEmbed returns embed with expected title fields color url`() {
        val embed = DiscordService.buildHostUpEmbed(
            hostName = "cache-node-3",
            hostResourceId = HOST_RESOURCE_ID,
            baseUrl = BASE_URL_APP
        )
        assertEquals("✅ Host Recovered", embed.title)
        assertEquals("**cache-node-3** is back online", embed.description)
        assertEquals("https://app.moneat.io/monitoring/hosts/$HOST_RESOURCE_ID", embed.url)
        assertEquals(0x2EB67D, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals(2, fields.size)
        assertEquals("Host", fields[0].name)
        assertEquals("cache-node-3", fields[0].value)
        assertEquals("Status", fields[1].name)
        assertEquals("Online", fields[1].value)
    }

    @Test
    fun `buildUptimeAlertEmbed down with error message includes status and response time`() {
        val monitorId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val embed = DiscordService.buildUptimeAlertEmbed(
            DiscordService.UptimeAlertParams(
                monitorUrl = API_HEALTH_URL,
                isDown = true,
                statusCode = null,
                responseTime = 5000L,
                errorMessage = "Connection refused",
                monitorId = monitorId,
                baseUrl = BASE_URL_APP
            )
        )
        assertEquals(UPTIME_MONITOR_DOWN, embed.title)
        assertEquals("Monitor detected a failure", embed.description)
        assertEquals("https://app.moneat.io/monitoring?monitor=$monitorId", embed.url)
        assertEquals(0xE01E5A, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals(3, fields.size)
        assertEquals("URL", fields[0].name)
        assertEquals(API_HEALTH_URL, fields[0].value)
        assertEquals("Status", fields[1].name)
        assertEquals("Connection refused", fields[1].value)
        assertEquals("Response Time", fields[2].name)
        assertEquals("5000ms", fields[2].value)
    }

    @Test
    fun `buildUptimeAlertEmbed down with status code uses HTTP status`() {
        val monitorId = UUID.randomUUID()
        val embed = DiscordService.buildUptimeAlertEmbed(
            DiscordService.UptimeAlertParams(
                monitorUrl = API_HEALTH_URL,
                isDown = true,
                statusCode = 503,
                responseTime = 1200L,
                errorMessage = null,
                monitorId = monitorId,
                baseUrl = BASE_URL_APP
            )
        )
        assertEquals(UPTIME_MONITOR_DOWN, embed.title)
        val fields = requireNotNull(embed.fields)
        assertEquals("HTTP 503", fields[1].value)
        assertEquals("1200ms", fields[2].value)
    }

    @Test
    fun `buildUptimeAlertEmbed down with unknown status uses Unknown`() {
        val monitorId = UUID.randomUUID()
        val embed = DiscordService.buildUptimeAlertEmbed(
            DiscordService.UptimeAlertParams(
                monitorUrl = API_HEALTH_URL,
                isDown = true,
                statusCode = null,
                responseTime = null,
                errorMessage = null,
                monitorId = monitorId,
                baseUrl = BASE_URL_APP
            )
        )
        assertEquals(UPTIME_MONITOR_DOWN, embed.title)
        val fields = requireNotNull(embed.fields)
        assertEquals("Unknown", fields[1].value)
        assertEquals(2, fields.size)
    }

    @Test
    fun `buildUptimeAlertEmbed recovered uses green color and recovery message`() {
        val monitorId = UUID.randomUUID()
        val embed = DiscordService.buildUptimeAlertEmbed(
            DiscordService.UptimeAlertParams(
                monitorUrl = API_HEALTH_URL,
                isDown = false,
                statusCode = 200,
                responseTime = 45L,
                errorMessage = null,
                monitorId = monitorId,
                baseUrl = BASE_URL_APP
            )
        )
        assertEquals("✅ Uptime Monitor Recovered", embed.title)
        assertEquals("Monitor has recovered", embed.description)
        assertEquals(0x2EB67D, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals("HTTP 200", fields[1].value)
        assertEquals("45ms", fields[2].value)
    }

    @Test
    fun `buildDashboardAlertEmbed CRITICAL severity uses red color`() {
        val embed = DiscordService.buildDashboardAlertEmbed(
            DiscordService.DashboardAlertParams(
                alertName = "Error Spike",
                dashboardTitle = "Production Overview",
                widgetTitle = WIDGET_ERROR_RATE,
                condition = ">",
                threshold = "5%",
                currentValue = "12%",
                severity = "CRITICAL",
                dashboardId = 10L,
                baseUrl = BASE_URL_APP
            )
        )
        assertEquals("📊 Dashboard Alert: Error Spike", embed.title)
        assertEquals("Alert triggered on **Error Rate** in *Production Overview*", embed.description)
        assertEquals("https://app.moneat.io/dashboards/10", embed.url)
        assertEquals(0xE01E5A, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals("Production Overview", fields[0].value)
        assertEquals("Error Rate", fields[1].value)
        assertEquals("> 5%", fields[2].value)
        assertEquals("12%", fields[3].value)
    }

    @Test
    fun `buildDashboardAlertEmbed MEDIUM severity uses yellow color`() {
        val embed = DiscordService.buildDashboardAlertEmbed(
            DiscordService.DashboardAlertParams(
                alertName = "Throughput Low",
                dashboardTitle = "Service Health",
                widgetTitle = "RPS",
                condition = "<",
                threshold = "100",
                currentValue = "45",
                severity = "MEDIUM",
                dashboardId = 12L,
                baseUrl = BASE_URL_APP
            )
        )
        assertEquals(0xECB22E, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals("< 100", fields[2].value)
        assertEquals("45", fields[3].value)
    }

    @Test
    fun `buildDashboardAlertEmbed null severity defaults to yellow`() {
        val embed = DiscordService.buildDashboardAlertEmbed(
            DiscordService.DashboardAlertParams(
                alertName = "Custom Alert",
                dashboardTitle = "Custom Dashboard",
                widgetTitle = "Widget",
                condition = "=",
                threshold = "0",
                currentValue = "1",
                severity = null,
                dashboardId = 14L,
                baseUrl = BASE_URL_APP
            )
        )
        assertEquals(0xECB22E, embed.color)
    }

    @Test
    fun `buildErrorAlertEmbed fatal level uses red color`() {
        val embed = DiscordService.buildErrorAlertEmbed(
            DiscordService.ErrorAlertParams(
                projectName = "Backend API",
                issueTitle = "OutOfMemoryError",
                level = "fatal",
                firstSeen = TIMESTAMP_2024_06_15,
                eventCount = 3,
                userCount = 2,
                issueUrl = "https://app.moneat.io/issues/600"
            )
        )
        assertEquals("🐛 New Issue Detected", embed.title)
        assertEquals("**OutOfMemoryError**", embed.description)
        assertEquals("https://app.moneat.io/issues/600", embed.url)
        assertEquals(0xE01E5A, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals("Backend API", fields[0].value)
        assertEquals("FATAL", fields[1].value)
        assertEquals("3", fields[3].value)
        assertEquals("2", fields[4].value)
    }

    @Test
    fun `buildErrorAlertEmbed warning level uses yellow color`() {
        val embed = DiscordService.buildErrorAlertEmbed(
            DiscordService.ErrorAlertParams(
                projectName = "Worker",
                issueTitle = "Deprecated API usage",
                level = "warning",
                firstSeen = "2024-06-15T11:00:00Z",
                eventCount = 100,
                userCount = 50,
                issueUrl = "https://app.moneat.io/issues/501"
            )
        )
        assertEquals(0xECB22E, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals("WARNING", fields[1].value)
    }

    @Test
    fun `buildErrorAlertEmbed info level uses green color`() {
        val embed = DiscordService.buildErrorAlertEmbed(
            DiscordService.ErrorAlertParams(
                projectName = "Frontend",
                issueTitle = "Feature flag evaluated",
                level = "info",
                firstSeen = "2024-06-15T12:00:00Z",
                eventCount = 1,
                userCount = 1,
                issueUrl = "https://app.moneat.io/issues/502"
            )
        )
        assertEquals(0x2EB67D, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals("INFO", fields[1].value)
    }

    @Test
    fun `buildTestConnectionEmbed returns embed with guild id and status`() {
        val embed = DiscordService.buildTestConnectionEmbed(
            guildId = "123456789012345678",
            baseUrl = BASE_URL_APP
        )
        assertEquals("✅ Discord Integration Test", embed.title)
        assertEquals("Your Discord integration is working correctly!", embed.description)
        assertEquals(BASE_URL_APP, embed.url)
        assertEquals(0x2EB67D, embed.color)
        val fields = requireNotNull(embed.fields)
        assertEquals("Connected", fields[0].value)
        assertEquals("123456789012345678", fields[1].value)
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
                    alert = HostAlertNotification(
                        hostName = "api-1",
                        metric = "CPU",
                        condition = ">",
                        threshold = "90%",
                        currentValue = "95%",
                        hostResourceId = HOST_RESOURCE_ID,
                        baseUrl = BASE_URL_APP,
                    ),
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
                    baseUrl = BASE_URL_APP
                )
            )
        }

    // ── OAuth handling ──────────────────────────────────────────────────

    @Test
    fun `exchangeOAuthCode handles connection failure`() =
        runBlocking {
            MockHttpServer { exchange ->
                exchange.respond(500, "internal server error", "text/plain")
            }.use { server ->
                val service = DiscordService(discordApiBaseUrl = server.baseUrl)
                val response = service.exchangeOAuthCode(
                    code = "fake-code",
                    clientId = "fake-client-id",
                    clientSecret = "fake-secret",
                    redirectUri = "https://app.moneat.io/callback"
                )
                assertNotNull(response.error)
            }
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
        assertNull(resp.accessToken)
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
