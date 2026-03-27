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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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

class SlackService {
    private val logger = LoggerFactory.getLogger(SlackService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                socketTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 10_000
            }
        }

    @Serializable
    data class SlackBlock(
        val type: String,
        val text: SlackText? = null,
        val fields: List<SlackText>? = null,
        val elements: List<SlackElement>? = null,
        val accessory: SlackElement? = null
    )

    @Serializable
    data class SlackText(
        val type: String,
        val text: String,
        val emoji: Boolean? = null
    )

    @Serializable
    data class SlackElement(
        val type: String,
        val text: SlackText? = null,
        val url: String? = null,
        val action_id: String? = null
    )

    @Serializable
    data class SlackContextElement(
        val type: String,
        val text: String
    )

    @Serializable
    data class SlackMessage(
        val channel: String,
        val blocks: List<SlackBlock>? = null,
        val attachments: List<SlackAttachment>? = null,
        val text: String? = null // Fallback text for notifications
    )

    @Serializable
    data class SlackAttachment(
        val color: String? = null,
        val blocks: List<SlackBlock>? = null,
        val fallback: String? = null
    )

    @Serializable
    data class SlackOAuthResponse(
        val ok: Boolean,
        val access_token: String? = null,
        val token_type: String? = null,
        val scope: String? = null,
        val bot_user_id: String? = null,
        val app_id: String? = null,
        val team: SlackTeam? = null,
        val error: String? = null
    )

    @Serializable
    data class SlackTeam(
        val id: String,
        val name: String
    )

    @Serializable
    data class SlackPostMessageResponse(
        val ok: Boolean,
        val error: String? = null
    )

    @Serializable
    data class SlackConversationJoinResponse(
        val ok: Boolean,
        val error: String? = null
    )

    private data class SlackConfig(
        val accessToken: String,
        val channelId: String
    )

    private fun getSlackConfig(organizationId: Int): SlackConfig? {
        return transaction {
            OrganizationIntegrations
                .selectAll()
                .where {
                    (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "slack") and
                        (OrganizationIntegrations.enabled eq true)
                }.singleOrNull()
                ?.let { row ->
                    val token = row[OrganizationIntegrations.access_token]
                    val channel = row[OrganizationIntegrations.channel_id]
                    if (token != null && channel != null) {
                        SlackConfig(token, channel)
                    } else {
                        null
                    }
                }
        }
    }

    private suspend fun joinChannel(
        accessToken: String,
        channel: String
    ): Pair<Boolean, String?> {
        return try {
            val response: HttpResponse =
                httpClient.post("https://slack.com/api/conversations.join") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                    setBody("""{"channel":"$channel"}""")
                }

            val result = json.decodeFromString<SlackConversationJoinResponse>(response.bodyAsText())
            if (result.ok) {
                logger.debug("Successfully joined Slack channel $channel")
                true to null
            } else {
                // already_in_channel is fine, it means we're already there
                if (result.error == "already_in_channel") {
                    logger.debug("Already in Slack channel $channel")
                    true to null
                } else if (result.error == "missing_scope") {
                    logger.warn("Missing 'channels:join' permission for Slack app")
                    false to "Missing permission: Please add 'channels:join' scope to your Slack app and reinstall it"
                } else {
                    logger.warn("Could not join Slack channel: ${result.error}")
                    false to "Could not join channel: ${result.error}"
                }
            }
        } catch (e: SerializationException) {
            logger.warn("Error joining Slack channel", e)
            false to "Error joining channel: ${e.message}"
        } catch (e: IOException) {
            logger.warn("Error joining Slack channel", e)
            false to "Error joining channel: ${e.message}"
        } catch (e: IllegalStateException) {
            logger.warn("Error joining Slack channel", e)
            false to "Error joining channel: ${e.message}"
        } catch (e: IllegalArgumentException) {
            logger.warn("Error joining Slack channel", e)
            false to "Error joining channel: ${e.message}"
        }
    }

    private suspend fun sendMessage(
        accessToken: String,
        channel: String,
        blocks: List<SlackBlock>? = null,
        attachments: List<SlackAttachment>? = null,
        fallbackText: String
    ): Pair<Boolean, String?> {
        return try {
            // Attempt to join the channel first (if not already in it)
            val (joinSuccess, joinError) = joinChannel(accessToken, channel)
            if (!joinSuccess && joinError != null) {
                logger.error("Cannot send message: $joinError")
                return false to joinError
            }

            val message =
                SlackMessage(
                    channel = channel,
                    blocks = blocks,
                    attachments = attachments,
                    text = fallbackText
                )

            val messageJson = json.encodeToString(message)
            logger.debug("Sending Slack message: $messageJson")

            val response: HttpResponse =
                httpClient.post("https://slack.com/api/chat.postMessage") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                    setBody(messageJson)
                }

            if (response.status == HttpStatusCode.OK) {
                val responseBody = response.bodyAsText()
                val result = json.decodeFromString<SlackPostMessageResponse>(responseBody)
                if (result.ok) {
                    logger.debug("Successfully sent message to Slack")
                    true to null
                } else {
                    val errorMsg = result.error ?: "unknown error"
                    logger.error("Slack API returned error: $errorMsg")
                    logger.error("Full response: $responseBody")
                    false to "Slack error: $errorMsg"
                }
            } else {
                val errorMsg = "HTTP ${response.status}"
                logger.error("Failed to send to Slack: ${response.status} - ${response.bodyAsText()}")
                false to errorMsg
            }
        } catch (e: SerializationException) {
            logger.error("Error sending to Slack", e)
            false to "Error: ${e.message}"
        } catch (e: IOException) {
            logger.error("Error sending to Slack", e)
            false to "Error: ${e.message}"
        } catch (e: IllegalStateException) {
            logger.error("Error sending to Slack", e)
            false to "Error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            logger.error("Error sending to Slack", e)
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
        val config = getSlackConfig(organizationId) ?: return false

        val mainBlocks =
            listOf(
                SlackBlock(
                    type = "header",
                    text =
                    SlackText(
                        type = "plain_text",
                        text = "⚠️ Host Alert",
                        emoji = true
                    )
                )
            )

        val attachmentBlocks =
            listOf(
                SlackBlock(
                    type = "section",
                    fields =
                    listOf(
                        SlackText(type = "mrkdwn", text = "*Host:*\n$hostName"),
                        SlackText(type = "mrkdwn", text = "*Metric:*\n$metric"),
                        SlackText(type = "mrkdwn", text = "*Condition:*\n$condition $threshold"),
                        SlackText(type = "mrkdwn", text = "*Current Value:*\n$currentValue")
                    )
                ),
                SlackBlock(
                    type = "actions",
                    elements =
                    listOf(
                        SlackElement(
                            type = "button",
                            text = SlackText(type = "plain_text", text = "View Host"),
                            url = "$baseUrl/monitoring/hosts/$hostId"
                        )
                    )
                )
            )

        val attachments =
            listOf(
                SlackAttachment(
                    color = "#ECB22E", // Warning yellow
                    blocks = attachmentBlocks,
                    fallback = "⚠️ Host Alert: $hostName"
                )
            )

        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = mainBlocks,
                attachments = attachments,
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
        val config = getSlackConfig(organizationId) ?: return false

        val mainBlocks =
            listOf(
                SlackBlock(
                    type = "header",
                    text =
                    SlackText(
                        type = "plain_text",
                        text = "🔴 Host Down",
                        emoji = true
                    )
                )
            )

        val attachmentBlocks =
            listOf(
                SlackBlock(
                    type = "section",
                    fields =
                    listOf(
                        SlackText(type = "mrkdwn", text = "*Host:*\n$hostName"),
                        SlackText(type = "mrkdwn", text = "*Last Seen:*\n$lastSeen")
                    )
                ),
                SlackBlock(
                    type = "actions",
                    elements =
                    listOf(
                        SlackElement(
                            type = "button",
                            text = SlackText(type = "plain_text", text = "View Host"),
                            url = "$baseUrl/monitoring/hosts/$hostId"
                        )
                    )
                )
            )

        val attachments =
            listOf(
                SlackAttachment(
                    color = "#E01E5A", // Error red
                    blocks = attachmentBlocks,
                    fallback = "🔴 Host Down: $hostName"
                )
            )

        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = mainBlocks,
                attachments = attachments,
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
        val config = getSlackConfig(organizationId) ?: return false

        val mainBlocks =
            listOf(
                SlackBlock(
                    type = "header",
                    text =
                    SlackText(
                        type = "plain_text",
                        text = "🟢 Host Recovered",
                        emoji = true
                    )
                )
            )

        val attachmentBlocks =
            listOf(
                SlackBlock(
                    type = "section",
                    text =
                    SlackText(
                        type = "mrkdwn",
                        text = "*Host:* $hostName\n\nThe host is now reporting data again."
                    )
                ),
                SlackBlock(
                    type = "actions",
                    elements =
                    listOf(
                        SlackElement(
                            type = "button",
                            text = SlackText(type = "plain_text", text = "View Host"),
                            url = "$baseUrl/monitoring/hosts/$hostId"
                        )
                    )
                )
            )

        val attachments =
            listOf(
                SlackAttachment(
                    color = "#2EB67D", // Success green
                    blocks = attachmentBlocks,
                    fallback = "🟢 Host Recovered: $hostName"
                )
            )

        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = mainBlocks,
                attachments = attachments,
                fallbackText = "🟢 Host Recovered: $hostName"
            )
        return success
    }

    suspend fun sendUptimeAlert(
        organizationId: Int,
        monitorName: String,
        oldStatus: String,
        newStatus: String,
        message: String,
        monitorId: UUID,
        baseUrl: String
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return false

        val isDown = newStatus.equals("down", ignoreCase = true)
        val emoji = if (isDown) "🔴" else "🟢"
        val headerText = if (isDown) "Monitor Down" else "Monitor Recovered"
        val color = if (isDown) "#E01E5A" else "#2EB67D"

        val mainBlocks =
            listOf(
                SlackBlock(
                    type = "header",
                    text =
                    SlackText(
                        type = "plain_text",
                        text = "$emoji $headerText",
                        emoji = true
                    )
                )
            )

        val attachmentBlocks =
            listOf(
                SlackBlock(
                    type = "section",
                    fields =
                    listOf(
                        SlackText(type = "mrkdwn", text = "*Monitor:*\n$monitorName"),
                        SlackText(type = "mrkdwn", text = "*Status:*\n$oldStatus → $newStatus"),
                        SlackText(type = "mrkdwn", text = "*Message:*\n$message")
                    )
                ),
                SlackBlock(
                    type = "actions",
                    elements =
                    listOf(
                        SlackElement(
                            type = "button",
                            text = SlackText(type = "plain_text", text = "View Monitor"),
                            url = "$baseUrl/uptime?monitor=$monitorId"
                        )
                    )
                )
            )

        val attachments =
            listOf(
                SlackAttachment(
                    color = color,
                    blocks = attachmentBlocks,
                    fallback = "$emoji Monitor $headerText: $monitorName"
                )
            )

        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = mainBlocks,
                attachments = attachments,
                fallbackText = "$emoji Monitor $headerText: $monitorName - $message"
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
        val config = getSlackConfig(organizationId) ?: return false

        val emoji = if (severity == "CRITICAL" || severity == "HIGH") "🔴" else "⚠️"
        val color = when (severity) {
            "CRITICAL" -> "#E01E5A"
            "HIGH" -> "#E01E5A"
            "MEDIUM" -> "#ECB22E"
            else -> "#ECB22E"
        }

        val mainBlocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(type = "plain_text", text = "$emoji Dashboard Alert: $alertName", emoji = true)
            )
        )

        val attachmentBlocks = listOf(
            SlackBlock(
                type = "section",
                fields = listOf(
                    SlackText(type = "mrkdwn", text = "*Dashboard:*\n$dashboardTitle"),
                    SlackText(type = "mrkdwn", text = "*Widget:*\n$widgetTitle"),
                    SlackText(type = "mrkdwn", text = "*Condition:*\n$condition $threshold"),
                    SlackText(type = "mrkdwn", text = "*Current Value:*\n$currentValue")
                )
            ),
            SlackBlock(
                type = "actions",
                elements = listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = "View Dashboard"),
                        url = "$baseUrl/dashboards/$dashboardId"
                    )
                )
            )
        )

        val attachments = listOf(
            SlackAttachment(
                color = color,
                blocks = attachmentBlocks,
                fallback = "$emoji Dashboard Alert: $alertName"
            )
        )

        val (success, _) = sendMessage(
            accessToken = config.accessToken,
            channel = config.channelId,
            blocks = mainBlocks,
            attachments = attachments,
            fallbackText = "$emoji Dashboard Alert: $alertName - $condition $threshold (current: $currentValue)"
        )
        return success
    }

    suspend fun sendErrorAlert(
        organizationId: Int,
        projectName: String,
        issueTitle: String,
        level: String,
        culprit: String?,
        issueId: Long,
        baseUrl: String,
        occurrenceCount: Int = 1,
        environment: String? = null,
        timestamp: String? = null,
        stackTrace: String? = null
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return false

        val levelLower = level.lowercase()
        val levelEmoji =
            when (levelLower) {
                "error" -> "🔴"
                "warning" -> "⚠️"
                "info" -> "ℹ️"
                else -> "⚠️"
            }

        val color =
            when (levelLower) {
                "error" -> "#E01E5A"

                // Slack red
                "warning" -> "#ECB22E"

                // Slack warning yellow
                "info" -> "#2EB67D"

                // Slack green
                else -> "#ECB22E"
            }

        // Header block
        val mainBlocks =
            listOf(
                SlackBlock(
                    type = "header",
                    text =
                    SlackText(
                        type = "plain_text",
                        text = "$levelEmoji New ${level.uppercase()}",
                        emoji = true
                    )
                )
            )

        // Attachment blocks
        val attachmentBlocks = mutableListOf<SlackBlock>()

        // Issue Title and Link
        attachmentBlocks.add(
            SlackBlock(
                type = "section",
                text =
                SlackText(
                    type = "mrkdwn",
                    text = "*<$baseUrl/issues/$issueId|$issueTitle>*"
                )
            )
        )

        // Fields
        val fields = mutableListOf<SlackText>()
        fields.add(SlackText(type = "mrkdwn", text = "*Project:*\n$projectName"))

        if (environment != null) {
            fields.add(
                SlackText(
                    type = "mrkdwn",
                    text = "*Environment:*\n${environment.replaceFirstChar {
                        if (it.isLowerCase()) {
                            it.titlecase(
                                Locale.getDefault()
                            )
                        } else {
                            it.toString()
                        }
                    }}"
                )
            )
        }

        if (culprit != null) {
            fields.add(SlackText(type = "mrkdwn", text = "*Culprit:*\n`$culprit`"))
        }

        if (occurrenceCount > 1) {
            fields.add(SlackText(type = "mrkdwn", text = "*Occurrences:*\n$occurrenceCount"))
        } else {
            fields.add(SlackText(type = "mrkdwn", text = "*Level:*\n${level.uppercase()}"))
        }

        if (timestamp != null) {
            fields.add(SlackText(type = "mrkdwn", text = "*Time:*\n$timestamp"))
        }

        attachmentBlocks.add(
            SlackBlock(
                type = "section",
                fields = fields
            )
        )

        // Stack trace
        if (stackTrace != null) {
            attachmentBlocks.add(
                SlackBlock(
                    type = "section",
                    text =
                    SlackText(
                        type = "mrkdwn",
                        text = "*Stack Trace:*\n```$stackTrace```"
                    )
                )
            )
        }

        // Actions
        attachmentBlocks.add(
            SlackBlock(
                type = "actions",
                elements =
                listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = "View Issue"),
                        url = "$baseUrl/issues/$issueId"
                    )
                )
            )
        )

        val attachments =
            listOf(
                SlackAttachment(
                    color = color,
                    blocks = attachmentBlocks,
                    fallback = "$levelEmoji New ${level.uppercase()}: $issueTitle"
                )
            )

        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = mainBlocks,
                attachments = attachments,
                fallbackText = "$levelEmoji New ${level.uppercase()}: $issueTitle in $projectName"
            )
        return success
    }

    suspend fun testConnection(organizationId: Int): Pair<Boolean, String> {
        val config = getSlackConfig(organizationId) ?: return false to "No Slack integration configured"

        val blocks =
            listOf(
                SlackBlock(
                    type = "header",
                    text =
                    SlackText(
                        type = "plain_text",
                        text = "✅ Test Message",
                        emoji = true
                    )
                ),
                SlackBlock(
                    type = "section",
                    text =
                    SlackText(
                        type = "mrkdwn",
                        text = "Your Slack integration is working correctly! You'll receive notifications here when alerts are triggered."
                    )
                )
            )

        return try {
            val (success, error) =
                sendMessage(
                    accessToken = config.accessToken,
                    channel = config.channelId,
                    blocks = blocks,
                    fallbackText = "✅ Test Message"
                )
            if (success) {
                true to "Test message sent successfully"
            } else {
                false to (error ?: "Failed to send test message")
            }
        } catch (e: SerializationException) {
            logger.error("Error testing Slack connection", e)
            false to "Error: ${e.message}"
        } catch (e: IOException) {
            logger.error("Error testing Slack connection", e)
            false to "Error: ${e.message}"
        } catch (e: IllegalStateException) {
            logger.error("Error testing Slack connection", e)
            false to "Error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            logger.error("Error testing Slack connection", e)
            false to "Error: ${e.message}"
        }
    }

    suspend fun exchangeOAuthCode(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): SlackOAuthResponse {
        return try {
            val response: HttpResponse =
                httpClient.post("https://slack.com/api/oauth.v2.access") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        "code=$code&client_id=$clientId&client_secret=$clientSecret&redirect_uri=$redirectUri"
                    )
                }

            json.decodeFromString<SlackOAuthResponse>(response.bodyAsText())
        } catch (e: SerializationException) {
            logger.error("Error exchanging OAuth code", e)
            SlackOAuthResponse(ok = false, error = e.message)
        } catch (e: IOException) {
            logger.error("Error exchanging OAuth code", e)
            SlackOAuthResponse(ok = false, error = e.message)
        } catch (e: IllegalStateException) {
            logger.error("Error exchanging OAuth code", e)
            SlackOAuthResponse(ok = false, error = e.message)
        } catch (e: IllegalArgumentException) {
            logger.error("Error exchanging OAuth code", e)
            SlackOAuthResponse(ok = false, error = e.message)
        }
    }

    @Serializable
    data class SlackChannelsResponse(
        val ok: Boolean,
        val channels: List<SlackChannel>? = null,
        val error: String? = null
    )

    @Serializable
    data class SlackChannel(
        val id: String,
        val name: String,
        val is_private: Boolean? = null
    )

    suspend fun listChannels(accessToken: String): List<SlackChannel> {
        return try {
            val response: HttpResponse =
                httpClient.get("https://slack.com/api/conversations.list") {
                    header("Authorization", "Bearer $accessToken")
                    parameter("types", "public_channel,private_channel")
                    parameter("limit", 200)
                }

            val result = json.decodeFromString<SlackChannelsResponse>(response.bodyAsText())
            if (result.ok && result.channels != null) {
                result.channels
            } else {
                logger.error("Failed to list Slack channels: ${result.error}")
                emptyList()
            }
        } catch (e: SerializationException) {
            logger.error("Error listing Slack channels", e)
            emptyList()
        } catch (e: IOException) {
            logger.error("Error listing Slack channels", e)
            emptyList()
        } catch (e: IllegalStateException) {
            logger.error("Error listing Slack channels", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            logger.error("Error listing Slack channels", e)
            emptyList()
        }
    }

    // ===== User Groups =====

    @Serializable
    data class SlackUsergroupsResponse(
        val ok: Boolean,
        val usergroups: List<SlackUsergroup>? = null,
        val error: String? = null
    )

    @Serializable
    data class SlackUsergroup(
        val id: String,
        val handle: String,
        val name: String,
        val description: String? = null
    )

    @Serializable
    data class SlackUsergroupUsersUpdateResponse(
        val ok: Boolean,
        val error: String? = null
    )

    suspend fun listUsergroups(accessToken: String): List<SlackUsergroup> {
        return try {
            val response: HttpResponse =
                httpClient.get("https://slack.com/api/usergroups.list") {
                    header("Authorization", "Bearer $accessToken")
                }

            val result = json.decodeFromString<SlackUsergroupsResponse>(response.bodyAsText())
            if (result.ok && result.usergroups != null) {
                result.usergroups
            } else {
                logger.error("Failed to list Slack usergroups: ${result.error}")
                emptyList()
            }
        } catch (e: SerializationException) {
            logger.error("Error listing Slack usergroups", e)
            emptyList()
        } catch (e: IOException) {
            logger.error("Error listing Slack usergroups", e)
            emptyList()
        } catch (e: IllegalStateException) {
            logger.error("Error listing Slack usergroups", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            logger.error("Error listing Slack usergroups", e)
            emptyList()
        }
    }

    suspend fun updateUsergroupMembers(
        accessToken: String,
        usergroupId: String,
        userIds: List<String>
    ): Boolean {
        return try {
            val response: HttpResponse =
                httpClient.post("https://slack.com/api/usergroups.users.update") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    header("Authorization", "Bearer $accessToken")
                    setBody("usergroup=$usergroupId&users=${userIds.joinToString(",")}")
                }

            val result = json.decodeFromString<SlackUsergroupUsersUpdateResponse>(response.bodyAsText())
            if (result.ok) {
                logger.debug("Successfully updated usergroup $usergroupId with ${userIds.size} members")
                true
            } else {
                logger.error("Failed to update Slack usergroup: ${result.error}")
                false
            }
        } catch (e: SerializationException) {
            logger.error("Error updating Slack usergroup members", e)
            false
        } catch (e: IOException) {
            logger.error("Error updating Slack usergroup members", e)
            false
        } catch (e: IllegalStateException) {
            logger.error("Error updating Slack usergroup members", e)
            false
        } catch (e: IllegalArgumentException) {
            logger.error("Error updating Slack usergroup members", e)
            false
        }
    }

    // ===== On-Call Notifications =====

    suspend fun sendOnCallAlert(
        userId: Int,
        incidentId: Int,
        title: String,
        priorityLevel: String
    ) {
        try {
            // Get Slack user mapping
            val slackUserId =
                getSlackUserIdForUser(userId) ?: run {
                    logger.debug("No Slack mapping found for user $userId")
                    return
                }

            // Get organization's bot token
            val orgId = getUserOrganizationId(userId) ?: return
            val accessToken = getAccessToken(orgId) ?: return

            // Send DM with interactive buttons
            val blocks =
                listOf(
                    SlackBlock(
                        type = "header",
                        text = SlackText(type = "plain_text", text = "🚨 On-Call Alert", emoji = true)
                    ),
                    SlackBlock(
                        type = "section",
                        text = SlackText(type = "mrkdwn", text = "*Priority:* $priorityLevel\n*Incident:* $title")
                    ),
                    SlackBlock(
                        type = "actions",
                        elements =
                        listOf(
                            SlackElement(
                                type = "button",
                                text = SlackText(type = "plain_text", text = "Acknowledge", emoji = false),
                                action_id = "incident_acknowledge_$incidentId"
                            ),
                            SlackElement(
                                type = "button",
                                text = SlackText(type = "plain_text", text = "View Details", emoji = false),
                                url = "${com.moneat.config.EnvConfig.get(
                                    "FRONTEND_URL",
                                    "https://moneat.io"
                                )}/on-call/incidents/$incidentId",
                                action_id = "incident_view_$incidentId"
                            )
                        )
                    )
                )

            val response =
                httpClient.post("https://slack.com/api/chat.postMessage") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                    setBody(
                        SlackMessage(
                            channel = slackUserId, // DM to user
                            blocks = blocks,
                            text = "[$priorityLevel] $title" // Fallback
                        )
                    )
                }

            val result = json.decodeFromString<SlackPostMessageResponse>(response.bodyAsText())
            if (result.ok) {
                logger.info("Sent on-call Slack DM to user $userId for incident $incidentId")
            } else {
                logger.error("Failed to send on-call Slack DM: ${result.error}")
            }
        } catch (e: SerializationException) {
            logger.error("Error sending on-call Slack alert", e)
        } catch (e: IOException) {
            logger.error("Error sending on-call Slack alert", e)
        } catch (e: IllegalStateException) {
            logger.error("Error sending on-call Slack alert", e)
        } catch (e: IllegalArgumentException) {
            logger.error("Error sending on-call Slack alert", e)
        }
    }

    private fun getSlackUserIdForUser(userId: Int): String? =
        transaction {
            com.moneat.shared.models.SlackUserMappings
                .selectAll()
                .where { com.moneat.shared.models.SlackUserMappings.userId eq userId }
                .singleOrNull()
                ?.get(com.moneat.shared.models.SlackUserMappings.slackUserId)
        }

    private fun getAccessToken(organizationId: Int): String? =
        transaction {
            OrganizationIntegrations
                .selectAll()
                .where {
                    (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "slack") and
                        (OrganizationIntegrations.enabled eq true)
                }.limit(1)
                .singleOrNull()
                ?.get(OrganizationIntegrations.access_token)
        }

    private fun getUserOrganizationId(userId: Int): Int? =
        transaction {
            Memberships
                .selectAll()
                .where { Memberships.user_id eq userId }
                .limit(1)
                .singleOrNull()
                ?.get(Memberships.organization_id)
        }
}
