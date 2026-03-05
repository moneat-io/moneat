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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Clock

class DiscordService {
    private val logger = LoggerFactory.getLogger(DiscordService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val botToken = EnvConfig.get("DISCORD_BOT_TOKEN") ?: ""

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
        val access_token: String? = null,
        val token_type: String? = null,
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

        return try {
            val message =
                DiscordMessage(
                    content = fallbackText,
                    embeds = listOf(embed)
                )

            val messageJson = json.encodeToString(message)
            logger.debug("Sending Discord message: $messageJson")

            val response: HttpResponse =
                httpClient.post("https://discord.com/api/v10/channels/$channelId/messages") {
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
        } catch (e: Exception) {
            logger.error("Error sending to Discord", e)
            false to "Error: ${e.message}"
        }
    }

    suspend fun sendHostAlert(
        organizationId: Int,
        hostName: String,
        metric: String,
        condition: String,
        threshold: String,
        currentValue: String,
        hostId: Int,
        baseUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed =
            DiscordEmbed(
                title = "⚠️ Host Alert",
                description = "**$hostName** triggered an alert",
                url = "$baseUrl/monitoring/hosts/$hostId",
                color = 0xECB22E, // Warning yellow
                fields =
                listOf(
                    DiscordField("Host", hostName, true),
                    DiscordField("Metric", metric, true),
                    DiscordField("Condition", "$condition $threshold", true),
                    DiscordField("Current Value", currentValue, true)
                ),
                footer = DiscordFooter("Moneat Alert"),
                timestamp = Clock.System.now().toString()
            )

        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "⚠️ Host Alert: $hostName - $metric $condition $threshold (current: $currentValue)"
            )
        return success
    }

    suspend fun sendHostDown(
        organizationId: Int,
        hostName: String,
        lastSeen: String,
        hostId: Int,
        baseUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed =
            DiscordEmbed(
                title = "🔴 Host Down",
                description = "**$hostName** is not responding",
                url = "$baseUrl/monitoring/hosts/$hostId",
                color = 0xE01E5A, // Error red
                fields =
                listOf(
                    DiscordField("Host", hostName, true),
                    DiscordField("Last Seen", lastSeen, true)
                ),
                footer = DiscordFooter("Moneat Alert"),
                timestamp = Clock.System.now().toString()
            )

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
        hostId: Int,
        baseUrl: String
    ): Boolean {
        val config = getDiscordConfig(organizationId) ?: return false

        val embed =
            DiscordEmbed(
                title = "✅ Host Recovered",
                description = "**$hostName** is back online",
                url = "$baseUrl/monitoring/hosts/$hostId",
                color = 0x2EB67D, // Success green
                fields =
                listOf(
                    DiscordField("Host", hostName, true),
                    DiscordField("Status", "Online", true)
                ),
                footer = DiscordFooter("Moneat Alert"),
                timestamp = Clock.System.now().toString()
            )

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

        val title = if (isDown) "🔴 Uptime Monitor Down" else "✅ Uptime Monitor Recovered"
        val color = if (isDown) 0xE01E5A else 0x2EB67D
        val statusText =
            when {
                errorMessage != null -> errorMessage
                statusCode != null -> "HTTP $statusCode"
                else -> "Unknown"
            }

        val fields =
            mutableListOf(
                DiscordField("URL", monitorUrl, false),
                DiscordField("Status", statusText, true)
            )

        if (responseTime != null) {
            fields.add(DiscordField("Response Time", "${responseTime}ms", true))
        }

        val embed =
            DiscordEmbed(
                title = title,
                description = if (isDown) "Monitor detected a failure" else "Monitor has recovered",
                url = "$baseUrl/monitoring?monitor=$monitorId",
                color = color,
                fields = fields,
                footer = DiscordFooter("Moneat Uptime Monitor"),
                timestamp = Clock.System.now().toString()
            )

        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "$title: $monitorUrl"
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

        val color = when (severity) {
            "CRITICAL" -> 0xE01E5A
            "HIGH" -> 0xE01E5A
            "MEDIUM" -> 0xECB22E
            else -> 0xECB22E
        }

        val embed = DiscordEmbed(
            title = "📊 Dashboard Alert: $alertName",
            description = "Alert triggered on **$widgetTitle** in *$dashboardTitle*",
            url = "$baseUrl/dashboards/$dashboardId",
            color = color,
            fields = listOf(
                DiscordField("Dashboard", dashboardTitle, true),
                DiscordField("Widget", widgetTitle, true),
                DiscordField("Condition", "$condition $threshold", true),
                DiscordField("Current Value", currentValue, true)
            ),
            footer = DiscordFooter("Moneat Dashboard Alert"),
            timestamp = Clock.System.now().toString()
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

        val color =
            when (level.lowercase()) {
                "fatal", "error" -> 0xE01E5A
                "warning" -> 0xECB22E
                else -> 0x2EB67D
            }

        val embed =
            DiscordEmbed(
                title = "🐛 New Issue Detected",
                description = "**$issueTitle**",
                url = issueUrl,
                color = color,
                fields =
                listOf(
                    DiscordField("Project", projectName, true),
                    DiscordField("Level", level.uppercase(), true),
                    DiscordField("First Seen", firstSeen, true),
                    DiscordField("Events", "$eventCount", true),
                    DiscordField("Users", "$userCount", true)
                ),
                footer = DiscordFooter("Moneat Error Tracking"),
                timestamp = Clock.System.now().toString()
            )

        val (success, _) =
            sendMessage(
                channelId = config.channelId,
                embed = embed,
                fallbackText = "🐛 New Issue: $issueTitle in $projectName"
            )
        return success
    }

    suspend fun testConnection(
        organizationId: Int,
        baseUrl: String
    ): Pair<Boolean, String?> {
        val config = getDiscordConfig(organizationId) ?: return false to "Discord not configured"

        val embed =
            DiscordEmbed(
                title = "✅ Discord Integration Test",
                description = "Your Discord integration is working correctly!",
                url = baseUrl,
                color = 0x2EB67D,
                fields =
                listOf(
                    DiscordField("Status", "Connected", true),
                    DiscordField("Guild ID", config.guildId, true)
                ),
                footer = DiscordFooter("Moneat"),
                timestamp = Clock.System.now().toString()
            )

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
        return try {
            val response: HttpResponse =
                httpClient.post("https://discord.com/api/v10/oauth2/token") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        "grant_type=authorization_code&code=$code&client_id=$clientId&client_secret=$clientSecret&redirect_uri=$redirectUri"
                    )
                }

            json.decodeFromString<DiscordOAuthResponse>(response.bodyAsText())
        } catch (e: Exception) {
            logger.error("Error exchanging Discord OAuth code", e)
            DiscordOAuthResponse(error = e.message)
        }
    }

    suspend fun listChannels(guildId: String): List<DiscordChannel> {
        if (botToken.isBlank()) {
            logger.error("DISCORD_BOT_TOKEN not configured")
            return emptyList()
        }

        return try {
            val response: HttpResponse =
                httpClient.get("https://discord.com/api/v10/guilds/$guildId/channels") {
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
        } catch (e: Exception) {
            logger.error("Error listing Discord channels", e)
            emptyList()
        }
    }
}
