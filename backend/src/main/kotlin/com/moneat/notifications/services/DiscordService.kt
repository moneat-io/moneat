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

package com.moneat.notifications.services

import com.moneat.config.EnvConfig
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.workflows.models.WorkflowStepPreview
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import com.moneat.utils.suspendRunCatching

class DiscordService(
    private val discordApiBaseUrl: String? = null
) {
    private val logger = LoggerFactory.getLogger(DiscordService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val botToken = EnvConfig.get("DISCORD_BOT_TOKEN") ?: ""

    private val apiBase: String
        get() = discordApiBaseUrl ?: "https://discord.com/api/v10"

    private val httpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                socketTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 10_000
            }
        }

    @Serializable
    data class DiscordEmbed(
        val title: String? = null,
        val description: String? = null,
        val url: String? = null,
        val color: Int? = null,
        val fields: List<DiscordField>? = null,
        val footer: DiscordFooter? = null,
        val timestamp: String? = null
    )

    @Serializable
    data class DiscordField(
        val name: String,
        val value: String,
        val inline: Boolean = true
    )

    @Serializable
    data class DiscordFooter(
        val text: String
    )

    @Serializable
    data class DiscordMessage(
        val content: String? = null,
        val embeds: List<DiscordEmbed>? = null
    )

    @Serializable
    data class DiscordOAuthResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val scope: String? = null,
        val guild: DiscordGuild? = null,
        val error: String? = null
    )

    @Serializable
    data class DiscordGuild(
        val id: String,
        val name: String
    )

    @Serializable
    data class DiscordChannel(
        val id: String,
        val name: String,
        val type: Int
    )

    data class UptimeAlertParams(
        val monitorUrl: String,
        val isDown: Boolean,
        val statusCode: Int?,
        val responseTime: Long?,
        val errorMessage: String?,
        val monitorId: UUID,
        val baseUrl: String,
        val timestamp: String = Clock.System.now().toString()
    )

    data class DashboardAlertParams(
        val alertName: String,
        val dashboardTitle: String,
        val widgetTitle: String,
        val condition: String,
        val threshold: String,
        val currentValue: String,
        val severity: String?,
        val dashboardId: Long,
        val baseUrl: String,
        val timestamp: String = Clock.System.now().toString()
    )

    data class ErrorAlertParams(
        val projectName: String,
        val issueTitle: String,
        val level: String,
        val firstSeen: String,
        val eventCount: Int,
        val userCount: Int,
        val issueUrl: String,
        val timestamp: String = Clock.System.now().toString()
    )

    companion object {
        private const val DISCORD_COLOR_RED = 0xE01E5A
        private const val DISCORD_COLOR_GREEN = 0x2EB67D
        private const val DISCORD_COLOR_YELLOW = 0xECB22E
        private const val DISCORD_COLOR_PURPLE = 0x6366F1

        /** Builds embed for host metric alerts. Exposed for unit testing. */
        internal fun buildHostAlertEmbed(p: HostAlertNotification): DiscordEmbed =
            DiscordEmbed(
                title = "⚠️ Host Alert",
                description = "**${p.hostName}** triggered an alert",
                url = "${p.baseUrl}/monitoring/hosts/${p.hostResourceId}",
                color = DISCORD_COLOR_YELLOW,
                fields = listOf(
                    DiscordField("Host", p.hostName, true),
                    DiscordField("Metric", p.metric, true),
                    DiscordField("Condition", "${p.condition} ${p.threshold}", true),
                    DiscordField("Current Value", p.currentValue, true)
                ),
                footer = DiscordFooter("Moneat Alert"),
                timestamp = p.timestamp
            )

        /** Builds embed for host down alerts. Exposed for unit testing. */
        internal fun buildHostDownEmbed(
            hostName: String,
            lastSeen: String,
            hostResourceId: String,
            baseUrl: String,
            timestamp: String = Clock.System.now().toString()
        ): DiscordEmbed =
            DiscordEmbed(
                title = "🔴 Host Down",
                description = "**$hostName** is not responding",
                url = "$baseUrl/monitoring/hosts/$hostResourceId",
                color = DISCORD_COLOR_RED,
                fields = listOf(
                    DiscordField("Host", hostName, true),
                    DiscordField("Last Seen", lastSeen, true)
                ),
                footer = DiscordFooter("Moneat Alert"),
                timestamp = timestamp
            )

        /** Builds embed for host recovered alerts. Exposed for unit testing. */
        internal fun buildHostUpEmbed(
            hostName: String,
            hostResourceId: String,
            baseUrl: String,
            timestamp: String = Clock.System.now().toString()
        ): DiscordEmbed =
            DiscordEmbed(
                title = "✅ Host Recovered",
                description = "**$hostName** is back online",
                url = "$baseUrl/monitoring/hosts/$hostResourceId",
                color = DISCORD_COLOR_GREEN,
                fields = listOf(
                    DiscordField("Host", hostName, true),
                    DiscordField("Status", "Online", true)
                ),
                footer = DiscordFooter("Moneat Alert"),
                timestamp = timestamp
            )

        /** Builds embed for uptime monitor alerts. Exposed for unit testing. */
        internal fun buildUptimeAlertEmbed(p: DiscordService.UptimeAlertParams): DiscordEmbed {
            val title = if (p.isDown) "🔴 Uptime Monitor Down" else "✅ Uptime Monitor Recovered"
            val color = if (p.isDown) DISCORD_COLOR_RED else DISCORD_COLOR_GREEN
            val statusText = when {
                p.errorMessage != null -> p.errorMessage
                p.statusCode != null -> "HTTP ${p.statusCode}"
                else -> "Unknown"
            }
            val fields = mutableListOf(
                DiscordField("URL", p.monitorUrl, false),
                DiscordField("Status", statusText, true)
            )
            if (p.responseTime != null) {
                fields.add(DiscordField("Response Time", "${p.responseTime}ms", true))
            }
            return DiscordEmbed(
                title = title,
                description = if (p.isDown) "Monitor detected a failure" else "Monitor has recovered",
                url = "${p.baseUrl}/monitoring?monitor=${p.monitorId}",
                color = color,
                fields = fields,
                footer = DiscordFooter("Moneat Uptime Monitor"),
                timestamp = p.timestamp
            )
        }

        /** Builds embed for dashboard alerts. Exposed for unit testing. */
        internal fun buildDashboardAlertEmbed(p: DiscordService.DashboardAlertParams): DiscordEmbed {
            val color = when (p.severity) {
                "CRITICAL", "HIGH" -> DISCORD_COLOR_RED
                "MEDIUM" -> DISCORD_COLOR_YELLOW
                else -> DISCORD_COLOR_YELLOW
            }
            return DiscordEmbed(
                title = "📊 Dashboard Alert: ${p.alertName}",
                description = "Alert triggered on **${p.widgetTitle}** in *${p.dashboardTitle}*",
                url = "${p.baseUrl}/dashboards/${p.dashboardId}",
                color = color,
                fields = listOf(
                    DiscordField("Dashboard", p.dashboardTitle, true),
                    DiscordField("Widget", p.widgetTitle, true),
                    DiscordField("Condition", "${p.condition} ${p.threshold}", true),
                    DiscordField("Current Value", p.currentValue, true)
                ),
                footer = DiscordFooter("Moneat Dashboard Alert"),
                timestamp = p.timestamp
            )
        }

        /** Builds embed for error/issue alerts. Exposed for unit testing. */
        internal fun buildErrorAlertEmbed(p: DiscordService.ErrorAlertParams): DiscordEmbed {
            val color = when (p.level.lowercase()) {
                "fatal", "error" -> DISCORD_COLOR_RED
                "warning" -> DISCORD_COLOR_YELLOW
                else -> DISCORD_COLOR_GREEN
            }
            return DiscordEmbed(
                title = "🐛 New Issue Detected",
                description = "**${p.issueTitle}**",
                url = p.issueUrl,
                color = color,
                fields = listOf(
                    DiscordField("Project", p.projectName, true),
                    DiscordField("Level", p.level.uppercase(), true),
                    DiscordField("First Seen", p.firstSeen, true),
                    DiscordField("Events", "${p.eventCount}", true),
                    DiscordField("Users", "${p.userCount}", true)
                ),
                footer = DiscordFooter("Moneat Error Tracking"),
                timestamp = p.timestamp
            )
        }

        /** Builds embed for Discord integration test. Exposed for unit testing. */
        internal fun buildTestConnectionEmbed(
            guildId: String,
            baseUrl: String,
            timestamp: String = Clock.System.now().toString()
        ): DiscordEmbed =
            DiscordEmbed(
                title = "✅ Discord Integration Test",
                description = "Your Discord integration is working correctly!",
                url = baseUrl,
                color = DISCORD_COLOR_GREEN,
                fields = listOf(
                    DiscordField("Status", "Connected", true),
                    DiscordField("Guild ID", guildId, true)
                ),
                footer = DiscordFooter("Moneat"),
                timestamp = timestamp
            )
    }

    private data class DiscordConfig(
        val guildId: String,
        val channelId: String
    )

    private fun getDiscordConfig(organizationId: Int): DiscordConfig? {
        return transaction {
            OrganizationIntegrations
                .selectAll()
                .where {
                    (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "discord") and
                        (OrganizationIntegrations.enabled eq true)
                }.singleOrNull()
                ?.let { row ->
                    val guildId = row[OrganizationIntegrations.team_id]
                    val channel = row[OrganizationIntegrations.channel_id]
                    if (guildId != null && channel != null) {
                        DiscordConfig(guildId, channel)
                    } else {
                        null
                    }
                }
        }
    }

    private suspend fun sendMessage(
        channelId: String,
        embed: DiscordEmbed,
        fallbackText: String
    ): Pair<Boolean, String?> {
        if (botToken.isBlank()) {
            logger.error("DISCORD_BOT_TOKEN not configured")
            return false to "Discord bot token not configured"
        }

        return suspendRunCatching {
            val message =
                DiscordMessage(
                    content = fallbackText,
                    embeds = listOf(embed)
                )

            val messageJson = json.encodeToString(message)
            logger.debug("Sending Discord message: $messageJson")

            val response: HttpResponse =
                httpClient.post("$apiBase/channels/$channelId/messages") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bot $botToken")
                    setBody(messageJson)
                }

            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                logger.debug("Successfully sent message to Discord")
                true to null
            } else {
                val errorMsg = "HTTP ${response.status}"
                logger.error("Failed to send to Discord: ${response.status} - ${response.bodyAsText()}")
                false to errorMsg
            }
        }.getOrElse { e ->
            logger.error("Error sending to Discord", e)
            false to "Error: ${e.message}"
        }
    }

    suspend fun sendHostAlert(
        organizationId: Int,
        alert: HostAlertNotification,
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed = buildHostAlertEmbed(alert)

        val fallbackText =
            "⚠️ Host Alert: ${alert.hostName} - ${alert.metric} ${alert.condition} " +
                "${alert.threshold} (current: ${alert.currentValue})"
        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = fallbackText
            )
        return success
    }

    suspend fun sendHostDown(
        organizationId: Int,
        hostName: String,
        lastSeen: String,
        hostResourceId: String,
        baseUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed = buildHostDownEmbed(hostName, lastSeen, hostResourceId, baseUrl)

        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "🔴 Host Down: $hostName - $lastSeen"
            )
        return success
    }

    suspend fun sendHostUp(
        organizationId: Int,
        hostName: String,
        hostResourceId: String,
        baseUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed = buildHostUpEmbed(hostName, hostResourceId, baseUrl)

        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "✅ Host Recovered: $hostName"
            )
        return success
    }

    suspend fun sendUptimeAlert(
        organizationId: Int,
        monitorUrl: String,
        isDown: Boolean,
        statusCode: Int?,
        responseTime: Long?,
        errorMessage: String?,
        monitorId: UUID,
        baseUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed = buildUptimeAlertEmbed(
            UptimeAlertParams(monitorUrl, isDown, statusCode, responseTime, errorMessage, monitorId, baseUrl)
        )

        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "${embed.title}: $monitorUrl"
            )
        return success
    }

    suspend fun sendDashboardAlert(
        organizationId: Int,
        alertName: String,
        dashboardTitle: String,
        widgetTitle: String,
        condition: String,
        threshold: String,
        currentValue: String,
        severity: String?,
        dashboardId: Long,
        baseUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed = buildDashboardAlertEmbed(
            DashboardAlertParams(
                alertName,
                dashboardTitle,
                widgetTitle,
                condition,
                threshold,
                currentValue,
                severity,
                dashboardId,
                baseUrl
            )
        )

        val (success, _) = sendMessage(
            channelId = config.channelId,
            embed = embed,
            fallbackText = "📊 Dashboard Alert: $alertName - $condition $threshold (current: $currentValue)"
        )
        return success
    }

    suspend fun sendErrorAlert(
        organizationId: Int,
        projectName: String,
        issueTitle: String,
        level: String,
        firstSeen: String,
        eventCount: Int,
        userCount: Int,
        issueUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed = buildErrorAlertEmbed(
            ErrorAlertParams(projectName, issueTitle, level, firstSeen, eventCount, userCount, issueUrl)
        )

        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "🐛 New Issue: $issueTitle in $projectName"
            )
        return success
    }

    suspend fun sendWorkflowMessage(
        organizationId: Int,
        title: String,
        message: String,
        skipIfUnconfigured: Boolean = false
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return skipIfUnconfigured
        val embed = DiscordEmbed(
            title = title,
            description = message,
            color = DISCORD_COLOR_PURPLE,
            footer = DiscordFooter("Moneat workflow"),
            timestamp = Clock.System.now().toString()
        )
        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "$title: $message"
            )
        return success
    }

    suspend fun sendWorkflowAlertMessage(
        organizationId: Int,
        preview: WorkflowStepPreview,
        skipIfUnconfigured: Boolean = false
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return skipIfUnconfigured
        val embed =
            DiscordEmbed(
                title = discordWorkflowAlertHeaderText(preview),
                description = discordWorkflowAlertDescription(preview),
                color = parseHexColor(preview.color),
                fields = preview.fields.map { field ->
                    DiscordField(field.label, field.value, inline = true)
                },
                footer = DiscordFooter("Added by Moneat"),
                timestamp = Clock.System.now().toString()
            )
        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = preview.fallbackText
            )
        return success
    }

    private fun discordWorkflowAlertDescription(preview: WorkflowStepPreview): String =
        buildString {
            if (preview.body.isNotBlank()) {
                appendLine(preview.body)
            }
            if (!preview.ctaUrl.isNullOrBlank()) {
                appendLine()
                append("[")
                append(preview.ctaLabel ?: "View")
                append("](")
                append(preview.ctaUrl)
                append(")")
            }
        }.trim()

    private fun discordWorkflowAlertHeaderText(preview: WorkflowStepPreview): String {
        val emoji =
            when {
                discordWorkflowAlertFieldValue(preview, "Status") == "Resolved" -> "✅"
                parseHexColor(preview.color) == DISCORD_COLOR_RED -> "🔴"
                else -> "⚠️"
            }
        return listOf(emoji, preview.title)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun discordWorkflowAlertFieldValue(
        preview: WorkflowStepPreview,
        label: String
    ): String =
        preview.fields
            .firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?.value
            .orEmpty()

    suspend fun testConnection(
        organizationId: Int,
        baseUrl: String
    ): Pair<Boolean, String?> {
        val config = getDiscordConfig(organizationId) ?: return false to "Discord not configured"

        val embed = buildTestConnectionEmbed(config.guildId, baseUrl)

        return sendMessage(
            channelId = config.channelId,
            embed = embed,
            fallbackText = "✅ Discord Integration Test - Success!"
        )
    }

    suspend fun exchangeOAuthCode(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): DiscordOAuthResponse {
        return suspendRunCatching {
            val response: HttpResponse =
                httpClient.submitForm(
                    url = "$apiBase/oauth2/token",
                    formParameters = Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                        append("redirect_uri", redirectUri)
                    }
                )

            json.decodeFromString<DiscordOAuthResponse>(response.bodyAsText())
        }.getOrElse { e ->
            logger.error("Error exchanging Discord OAuth code", e)
            DiscordOAuthResponse(error = e.message)
        }
    }

    suspend fun listChannels(guildId: String): List<DiscordChannel> {
        if (botToken.isBlank()) {
            logger.error("DISCORD_BOT_TOKEN not configured")
            return emptyList()
        }

        return suspendRunCatching {
            val response: HttpResponse =
                httpClient.get("$apiBase/guilds/$guildId/channels") {
                    header(HttpHeaders.Authorization, "Bot $botToken")
                }

            if (response.status == HttpStatusCode.OK) {
                val allChannels = json.decodeFromString<List<DiscordChannel>>(response.bodyAsText())
                // Filter to text channels only (type 0)
                allChannels.filter { it.type == 0 }
            } else {
                logger.error("Failed to list Discord channels: ${response.status}")
                emptyList()
            }
        }.getOrElse { e ->
            logger.error("Error listing Discord channels", e)
            emptyList()
        }
    }
}

private fun parseHexColor(color: String): Int? {
    val normalized = color.removePrefix("#")
    return normalized.toIntOrNull(HEX_RADIX)
}

private const val HEX_RADIX = 16
