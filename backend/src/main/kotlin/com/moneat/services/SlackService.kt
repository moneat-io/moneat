package com.moneat.services

import com.moneat.models.OrganizationIntegrations
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.*

class SlackService {
    private val logger = LoggerFactory.getLogger(SlackService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val httpClient = HttpClient(CIO) {
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
        val text: String? = null  // Fallback text for notifications
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
                }
                .singleOrNull()
                ?.let { row ->
                    val token = row[OrganizationIntegrations.access_token]
                    val channel = row[OrganizationIntegrations.channel_id]
                    if (token != null && channel != null) {
                        SlackConfig(token, channel)
                    } else null
                }
        }
    }

    private suspend fun joinChannel(accessToken: String, channel: String): Pair<Boolean, String?> {
        return try {
            val response: HttpResponse = httpClient.post("https://slack.com/api/conversations.join") {
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
        } catch (e: Exception) {
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
            
            val message = SlackMessage(
                channel = channel,
                blocks = blocks,
                attachments = attachments,
                text = fallbackText
            )
            
            val messageJson = json.encodeToString(message)
            logger.debug("Sending Slack message: $messageJson")
            
            val response: HttpResponse = httpClient.post("https://slack.com/api/chat.postMessage") {
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
        } catch (e: Exception) {
            logger.error("Error sending to Slack", e)
            false to "Error: ${e.message}"
        }
    }

    suspend fun sendSystemAlert(
        organizationId: Int,
        systemName: String,
        metric: String,
        condition: String,
        threshold: String,
        currentValue: String,
        systemId: UUID,
        baseUrl: String
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return false
        
        val mainBlocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(
                    type = "plain_text",
                    text = "⚠️ System Alert",
                    emoji = true
                )
            )
        )
        
        val attachmentBlocks = listOf(
            SlackBlock(
                type = "section",
                fields = listOf(
                    SlackText(type = "mrkdwn", text = "*System:*\n$systemName"),
                    SlackText(type = "mrkdwn", text = "*Metric:*\n$metric"),
                    SlackText(type = "mrkdwn", text = "*Condition:*\n$condition $threshold"),
                    SlackText(type = "mrkdwn", text = "*Current Value:*\n$currentValue")
                )
            ),
            SlackBlock(
                type = "actions",
                elements = listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = "View System"),
                        url = "$baseUrl/monitoring?system=$systemId"
                    )
                )
            )
        )
        
        val attachments = listOf(
            SlackAttachment(
                color = "#ECB22E", // Warning yellow
                blocks = attachmentBlocks,
                fallback = "⚠️ System Alert: $systemName"
            )
        )
        
        val (success, _) = sendMessage(
            accessToken = config.accessToken,
            channel = config.channelId,
            blocks = mainBlocks,
            attachments = attachments,
            fallbackText = "⚠️ System Alert: $systemName - $metric $condition $threshold (current: $currentValue)"
        )
        return success
    }

    suspend fun sendSystemDown(
        organizationId: Int,
        systemName: String,
        lastSeen: String,
        systemId: UUID,
        baseUrl: String
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return false
        
        val mainBlocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(
                    type = "plain_text",
                    text = "🔴 System Down",
                    emoji = true
                )
            )
        )
        
        val attachmentBlocks = listOf(
            SlackBlock(
                type = "section",
                fields = listOf(
                    SlackText(type = "mrkdwn", text = "*System:*\n$systemName"),
                    SlackText(type = "mrkdwn", text = "*Last Seen:*\n$lastSeen")
                )
            ),
            SlackBlock(
                type = "actions",
                elements = listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = "View System"),
                        url = "$baseUrl/monitoring?system=$systemId"
                    )
                )
            )
        )
        
        val attachments = listOf(
            SlackAttachment(
                color = "#E01E5A", // Error red
                blocks = attachmentBlocks,
                fallback = "🔴 System Down: $systemName"
            )
        )
        
        val (success, _) = sendMessage(
            accessToken = config.accessToken,
            channel = config.channelId,
            blocks = mainBlocks,
            attachments = attachments,
            fallbackText = "🔴 System Down: $systemName - $lastSeen"
        )
        return success
    }

    suspend fun sendSystemUp(
        organizationId: Int,
        systemName: String,
        systemId: UUID,
        baseUrl: String
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return false
        
        val mainBlocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(
                    type = "plain_text",
                    text = "🟢 System Recovered",
                    emoji = true
                )
            )
        )
        
        val attachmentBlocks = listOf(
            SlackBlock(
                type = "section",
                text = SlackText(
                    type = "mrkdwn",
                    text = "*System:* $systemName\n\nThe system is now reporting data again."
                )
            ),
            SlackBlock(
                type = "actions",
                elements = listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = "View System"),
                        url = "$baseUrl/monitoring?system=$systemId"
                    )
                )
            )
        )
        
        val attachments = listOf(
            SlackAttachment(
                color = "#2EB67D", // Success green
                blocks = attachmentBlocks,
                fallback = "🟢 System Recovered: $systemName"
            )
        )
        
        val (success, _) = sendMessage(
            accessToken = config.accessToken,
            channel = config.channelId,
            blocks = mainBlocks,
            attachments = attachments,
            fallbackText = "🟢 System Recovered: $systemName"
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
        
        val mainBlocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(
                    type = "plain_text",
                    text = "$emoji $headerText",
                    emoji = true
                )
            )
        )
        
        val attachmentBlocks = listOf(
            SlackBlock(
                type = "section",
                fields = listOf(
                    SlackText(type = "mrkdwn", text = "*Monitor:*\n$monitorName"),
                    SlackText(type = "mrkdwn", text = "*Status:*\n$oldStatus → $newStatus"),
                    SlackText(type = "mrkdwn", text = "*Message:*\n$message")
                )
            ),
            SlackBlock(
                type = "actions",
                elements = listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = "View Monitor"),
                        url = "$baseUrl/uptime?monitor=$monitorId"
                    )
                )
            )
        )
        
        val attachments = listOf(
            SlackAttachment(
                color = color,
                blocks = attachmentBlocks,
                fallback = "$emoji Monitor $headerText: $monitorName"
            )
        )
        
        val (success, _) = sendMessage(
            accessToken = config.accessToken,
            channel = config.channelId,
            blocks = mainBlocks,
            attachments = attachments,
            fallbackText = "$emoji Monitor $headerText: $monitorName - $message"
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
        projectId: Long,
        baseUrl: String,
        occurrenceCount: Int = 1,
        environment: String? = null,
        timestamp: String? = null,
        stackTrace: String? = null
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return false
        
        val levelLower = level.lowercase()
        val levelEmoji = when (levelLower) {
            "error" -> "🔴"
            "warning" -> "⚠️"
            "info" -> "ℹ️"
            else -> "⚠️"
        }
        
        val color = when (levelLower) {
            "error" -> "#E01E5A" // Slack red
            "warning" -> "#ECB22E" // Slack warning yellow
            "info" -> "#2EB67D" // Slack green
            else -> "#ECB22E"
        }
        
        // Header block
        val mainBlocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(
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
                text = SlackText(
                    type = "mrkdwn",
                    text = "*<${baseUrl}/projects/$projectId/issues/$issueId|$issueTitle>*"
                )
            )
        )
        
        // Fields
        val fields = mutableListOf<SlackText>()
        fields.add(SlackText(type = "mrkdwn", text = "*Project:*\n$projectName"))
        
        if (environment != null) {
            fields.add(SlackText(type = "mrkdwn", text = "*Environment:*\n${environment.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"))
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
                    text = SlackText(
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
                elements = listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = "View Issue"),
                        url = "$baseUrl/projects/$projectId/issues/$issueId"
                    )
                )
            )
        )
        
        val attachments = listOf(
            SlackAttachment(
                color = color,
                blocks = attachmentBlocks,
                fallback = "$levelEmoji New ${level.uppercase()}: $issueTitle"
            )
        )
        
        val (success, _) = sendMessage(
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
        
        val blocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(
                    type = "plain_text",
                    text = "✅ Test Message",
                    emoji = true
                )
            ),
            SlackBlock(
                type = "section",
                text = SlackText(
                    type = "mrkdwn",
                    text = "Your Slack integration is working correctly! You'll receive notifications here when alerts are triggered."
                )
            )
        )
        
        return try {
            val (success, error) = sendMessage(
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
        } catch (e: Exception) {
            logger.error("Error testing Slack connection", e)
            false to "Error: ${e.message}"
        }
    }

    suspend fun exchangeOAuthCode(code: String, clientId: String, clientSecret: String, redirectUri: String): SlackOAuthResponse {
        return try {
            val response: HttpResponse = httpClient.post("https://slack.com/api/oauth.v2.access") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    "code=$code&client_id=$clientId&client_secret=$clientSecret&redirect_uri=$redirectUri"
                )
            }
            
            json.decodeFromString<SlackOAuthResponse>(response.bodyAsText())
        } catch (e: Exception) {
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
            val response: HttpResponse = httpClient.get("https://slack.com/api/conversations.list") {
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
        } catch (e: Exception) {
            logger.error("Error listing Slack channels", e)
            emptyList()
        }
    }
}
