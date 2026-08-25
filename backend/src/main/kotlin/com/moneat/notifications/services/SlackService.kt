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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import com.moneat.config.EnvConfig
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.SlackOutboundOperation
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import com.moneat.workflows.models.WorkflowStepPreview
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import com.moneat.utils.suspendRunCatching
import java.util.Locale
import java.util.UUID

private const val SLACK_CHANNEL_FETCH_LIMIT = 200
private const val SLACK_COLOR_RED = "#E01E5A"
private const val SLACK_COLOR_GREEN = "#2EB67D"
private const val SLACK_COLOR_YELLOW = "#ECB22E"
private const val SLACK_HTTP_TIMEOUT_MILLIS = 10_000L

internal fun encodeSlackIssueIdPathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun createSlackHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            socketTimeoutMillis = SLACK_HTTP_TIMEOUT_MILLIS
            connectTimeoutMillis = SLACK_HTTP_TIMEOUT_MILLIS
            requestTimeoutMillis = SLACK_HTTP_TIMEOUT_MILLIS
        }
    }

class SlackService(
    private val httpClient: HttpClient = createSlackHttpClient(),
    private val installationService: SlackInstallationService = SlackInstallationService(),
) {
    private val logger = LoggerFactory.getLogger(SlackService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

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
        @SerialName("action_id") val actionId: String? = null
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
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val scope: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("bot_user_id") val botUserId: String? = null,
        @SerialName("app_id") val appId: String? = null,
        val team: SlackTeam? = null,
        val enterprise: SlackTeam? = null,
        @SerialName("authed_user") val authedUser: SlackAuthedUser? = null,
        @SerialName("is_enterprise_install") val isEnterpriseInstall: Boolean = false,
        val error: String? = null
    )

    @Serializable
    data class SlackAuthedUser(
        val id: String,
        val scope: String? = null,
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )

    @Serializable
    data class SlackTeam(
        val id: String,
        val name: String
    )

    @Serializable
    data class SlackPostMessageResponse(
        val ok: Boolean,
        val error: String? = null,
        val channel: String? = null,
        val ts: String? = null,
    )

    @Serializable
    private data class SlackAuthTestResponse(
        val ok: Boolean,
        @SerialName("team_id") val teamId: String? = null,
        @SerialName("enterprise_id") val enterpriseId: String? = null,
        @SerialName("user_id") val userId: String? = null,
        val error: String? = null,
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
        val installationConfig = runCatching {
            installationService.defaultDeliveryConfig(organizationId)
        }.onFailure { error ->
            logger.warn("Unable to read encrypted Slack installation; checking legacy configuration", error)
        }.getOrNull()
        if (installationConfig != null) {
            return SlackConfig(
                accessToken = installationConfig.accessToken,
                channelId = installationConfig.channelId,
            )
        }
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
        return suspendRunCatching {
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
        }.getOrElse { e ->
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
        return suspendRunCatching {
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
        }.getOrElse { e ->
            logger.error("Error sending to Slack", e)
            false to "Error: ${e.message}"
        }
    }

    suspend fun sendOutboundDelivery(delivery: SlackOutboundDelivery): SlackOutboundSendResult {
        val accessToken = getSlackAccessToken(delivery.organizationId)
            ?: return SlackOutboundSendResult.Retry("Slack workspace is not configured")
        if (delivery.operation == SlackOutboundOperation.MESSAGE && delivery.channelId.isNullOrBlank()) {
            return SlackOutboundSendResult.Failed("Slack message is missing a destination channel")
        }
        return sendOutboundOperation(delivery, accessToken)
    }

    suspend fun openModal(
        organizationId: Int,
        installationId: Int,
        triggerId: String,
        view: JsonObject,
    ): Boolean {
        val config = installationService.botConfigByInternalId(organizationId, installationId) ?: return false
        return suspendRunCatching {
            val response = httpClient.post("https://slack.com/api/views.open") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${config.accessToken}")
                setBody(
                    buildJsonObject {
                        put("trigger_id", triggerId)
                        put("view", view)
                    }.toString(),
                )
            }
            if (response.status != HttpStatusCode.OK) return@suspendRunCatching false
            json.parseToJsonElement(response.bodyAsText()).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrElse { error ->
            logger.warn("Failed to open Slack modal", error)
            false
        }
    }

    private suspend fun sendOutboundOperation(
        delivery: SlackOutboundDelivery,
        accessToken: String,
    ): SlackOutboundSendResult = try {
        val response = httpClient.post("https://slack.com/api/${delivery.operation.endpoint}") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $accessToken")
            setBody(prepareOutboundPayload(delivery))
        }
        classifyOutboundResponse(response)
    } catch (error: IOException) {
        SlackOutboundSendResult.Retry(error.message ?: "Slack connection failed")
    } catch (error: SerializationException) {
        SlackOutboundSendResult.Retry(error.message ?: "Slack response could not be decoded")
    }

    private suspend fun classifyOutboundResponse(response: HttpResponse): SlackOutboundSendResult {
        if (response.status == HttpStatusCode.TooManyRequests) {
            val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                ?.coerceAtLeast(MIN_RATE_LIMIT_RETRY_SECONDS) ?: DEFAULT_RATE_LIMIT_RETRY_SECONDS
            return SlackOutboundSendResult.Retry(
                reason = "Slack rate limit",
                retryAt = Clock.System.now() + retryAfter.seconds,
                rateLimited = true,
            )
        }
        if (response.status.value >= SLACK_SERVER_ERROR_STATUS) {
            return SlackOutboundSendResult.Retry("Slack returned ${response.status}")
        }
        if (response.status != HttpStatusCode.OK) {
            return SlackOutboundSendResult.Failed("Slack returned ${response.status}")
        }
        val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val ok = result["ok"]?.jsonPrimitive?.booleanOrNull == true
        return if (ok) {
            SlackOutboundSendResult.Delivered(
                providerMessageId = result.providerId(),
                providerMessageTs = result["ts"]?.jsonPrimitive?.contentOrNull,
            )
        } else if (result["error"]?.jsonPrimitive?.contentOrNull == "ratelimited") {
            SlackOutboundSendResult.Retry("Slack rate limit", rateLimited = true)
        } else {
            SlackOutboundSendResult.Failed(
                result["error"]?.jsonPrimitive?.contentOrNull ?: "Slack rejected the operation",
            )
        }
    }

    private fun prepareOutboundPayload(delivery: SlackOutboundDelivery): String {
        if (delivery.operation != SlackOutboundOperation.MESSAGE) return delivery.payload
        val payload = json.parseToJsonElement(delivery.payload).jsonObject.toMutableMap()
        payload["client_msg_id"] = JsonPrimitive(delivery.idempotencyKey)
        return JsonObject(payload).toString()
    }

    private fun JsonObject.providerId(): String? {
        val channel = this["channel"]
        return when (channel) {
            is JsonPrimitive -> channel.contentOrNull
            is JsonObject -> channel["id"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    private fun getSlackAccessToken(organizationId: Int): String? = transaction {
        OrganizationIntegrations
            .selectAll()
            .where {
                (OrganizationIntegrations.organization_id eq organizationId) and
                    (OrganizationIntegrations.integration_type eq "slack") and
                    (OrganizationIntegrations.enabled eq true)
            }
            .limit(1)
            .singleOrNull()
            ?.get(OrganizationIntegrations.access_token)
    }

    suspend fun sendHostAlert(
        organizationId: Int,
        alert: HostAlertNotification,
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
                        SlackText(type = "mrkdwn", text = "*Host:*\n${alert.hostName}"),
                        SlackText(type = "mrkdwn", text = "*Metric:*\n${alert.metric}"),
                        SlackText(type = "mrkdwn", text = "*Condition:*\n${alert.condition} ${alert.threshold}"),
                        SlackText(type = "mrkdwn", text = "*Current Value:*\n${alert.currentValue}")
                    )
                ),
                SlackBlock(
                    type = "actions",
                    elements =
                    listOf(
                        SlackElement(
                            type = "button",
                            text = SlackText(type = "plain_text", text = "View"),
                            url = "${alert.baseUrl}/monitoring/hosts/${alert.hostResourceId}"
                        )
                    )
                )
            )

        val attachments =
            listOf(
                SlackAttachment(
                    color = SLACK_COLOR_YELLOW,
                    blocks = attachmentBlocks,
                    fallback = "⚠️ Host Alert: ${alert.hostName}"
                )
            )

        val fallbackText =
            "⚠️ Host Alert: ${alert.hostName} - ${alert.metric} ${alert.condition} " +
                "${alert.threshold} (current: ${alert.currentValue})"
        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = mainBlocks,
                attachments = attachments,
                fallbackText = fallbackText
            )
        return success
    }

    suspend fun sendWorkflowMessage(
        organizationId: Int,
        message: String,
        skipIfUnconfigured: Boolean = false
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return skipIfUnconfigured
        val blocks = listOf(
            SlackBlock(
                type = "header",
                text = SlackText(type = "plain_text", text = "Moneat workflow", emoji = true)
            ),
            SlackBlock(
                type = "section",
                text = SlackText(type = "mrkdwn", text = message)
            )
        )
        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = blocks,
                fallbackText = message
            )
        return success
    }

    suspend fun sendWorkflowAlertMessage(
        organizationId: Int,
        preview: WorkflowStepPreview,
        skipIfUnconfigured: Boolean = false
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return skipIfUnconfigured
        val blocks =
            listOf(
                SlackBlock(
                    type = "header",
                    text = SlackText(type = "plain_text", text = workflowAlertHeaderText(preview), emoji = true)
                )
            )
        val attachmentBlocks = buildWorkflowAlertSlackBlocks(preview)
        val attachments =
            listOf(
                SlackAttachment(
                    color = preview.color,
                    blocks = attachmentBlocks,
                    fallback = preview.fallbackText
                )
            )
        val (success, _) =
            sendMessage(
                accessToken = config.accessToken,
                channel = config.channelId,
                blocks = blocks,
                attachments = attachments,
                fallbackText = preview.fallbackText
            )
        return success
    }

    private fun buildWorkflowAlertSlackBlocks(preview: WorkflowStepPreview): List<SlackBlock> {
        val blocks = mutableListOf<SlackBlock>()
        if (preview.body.isNotBlank()) {
            blocks += SlackBlock(
                type = "section",
                text = SlackText(type = "mrkdwn", text = preview.body)
            )
        }
        if (preview.fields.isNotEmpty()) {
            blocks += SlackBlock(
                type = "section",
                fields = preview.fields.map { field ->
                    SlackText(type = "mrkdwn", text = "*${field.label}:*\n${field.value}")
                }
            )
        }
        val ctaUrl = preview.ctaUrl
        val ctaLabel = preview.ctaLabel
        if (!ctaUrl.isNullOrBlank() && !ctaLabel.isNullOrBlank()) {
            blocks += SlackBlock(
                type = "actions",
                elements =
                listOf(
                    SlackElement(
                        type = "button",
                        text = SlackText(type = "plain_text", text = ctaLabel),
                        url = ctaUrl
                    )
                )
            )
        }
        return blocks
    }

    private fun workflowAlertHeaderText(preview: WorkflowStepPreview): String {
        val emoji =
            when {
                workflowAlertFieldValue(preview, "Status") == "Resolved" -> "✅"
                preview.color == SLACK_COLOR_RED -> "🔴"
                else -> "⚠️"
            }
        return listOf(emoji, preview.title)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun workflowAlertFieldValue(
        preview: WorkflowStepPreview,
        label: String
    ): String =
        preview.fields
            .firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?.value
            .orEmpty()

    suspend fun sendHostDown(
        organizationId: Int,
        hostName: String,
        lastSeen: String,
        hostResourceId: String,
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
                            text = SlackText(type = "plain_text", text = "View"),
                            url = "$baseUrl/monitoring/hosts/$hostResourceId"
                        )
                    )
                )
            )

        val attachments =
            listOf(
                SlackAttachment(
                    color = SLACK_COLOR_RED,
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
        hostResourceId: String,
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
                            text = SlackText(type = "plain_text", text = "View"),
                            url = "$baseUrl/monitoring/hosts/$hostResourceId"
                        )
                    )
                )
            )

        val attachments =
            listOf(
                SlackAttachment(
                    color = SLACK_COLOR_GREEN,
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
        val color = if (isDown) SLACK_COLOR_RED else SLACK_COLOR_GREEN

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
                            text = SlackText(type = "plain_text", text = "View"),
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
            "CRITICAL" -> SLACK_COLOR_RED
            "HIGH" -> SLACK_COLOR_RED
            "MEDIUM" -> SLACK_COLOR_YELLOW
            else -> SLACK_COLOR_YELLOW
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
                        text = SlackText(type = "plain_text", text = "View"),
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
        issueId: String,
        baseUrl: String,
        occurrenceCount: Int = 1,
        environment: String? = null,
        timestamp: String? = null,
        stackTrace: String? = null
    ): Boolean {
        val config = getSlackConfig(organizationId) ?: return false
        val issueUrl = "$baseUrl/issues/${encodeSlackIssueIdPathSegment(issueId)}"

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
                "error" -> SLACK_COLOR_RED
                "warning" -> SLACK_COLOR_YELLOW
                "info" -> SLACK_COLOR_GREEN
                else -> SLACK_COLOR_YELLOW
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
                    text = "*<$issueUrl|$issueTitle>*"
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
                        text = SlackText(type = "plain_text", text = "View"),
                        url = issueUrl
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

        return testConnection(config.accessToken, config.channelId)
    }

    suspend fun testConnection(
        accessToken: String,
        channelId: String,
    ): Pair<Boolean, String> {
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
                        text =
                        "Your Slack integration is working correctly! " +
                            "You'll receive notifications here when alerts are triggered."
                    )
                )
            )

        return suspendRunCatching {
            val (success, error) =
                sendMessage(
                    accessToken = accessToken,
                    channel = channelId,
                    blocks = blocks,
                    fallbackText = "✅ Test Message"
                )
            if (success) {
                true to "Test message sent successfully"
            } else {
                false to (error ?: "Failed to send test message")
            }
        }.getOrElse { e ->
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
        return suspendRunCatching {
            val response: HttpResponse =
                httpClient.post("https://slack.com/api/oauth.v2.access") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        "code=$code&client_id=$clientId&client_secret=$clientSecret&redirect_uri=$redirectUri"
                    )
                }

            json.decodeFromString<SlackOAuthResponse>(response.bodyAsText())
        }.getOrElse { e ->
            logger.error("Error exchanging OAuth code", e)
            SlackOAuthResponse(ok = false, error = e.message)
        }
    }

    suspend fun probeAuthentication(accessToken: String): SlackAuthenticationProbe =
        suspendRunCatching {
            val response = httpClient.post("https://slack.com/api/auth.test") {
                header("Authorization", "Bearer $accessToken")
            }
            val result = json.decodeFromString<SlackAuthTestResponse>(response.bodyAsText())
            SlackAuthenticationProbe(
                ok = result.ok,
                teamId = result.teamId,
                enterpriseId = result.enterpriseId,
                botUserId = result.userId,
                error = result.error,
            )
        }.getOrElse { error ->
            logger.warn("Slack authentication health probe failed", error)
            SlackAuthenticationProbe(ok = false, error = "transport_error")
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
        @SerialName("is_private") val isPrivate: Boolean? = null
    )

    suspend fun listChannels(accessToken: String): List<SlackChannel> {
        return suspendRunCatching {
            val response: HttpResponse =
                httpClient.get("https://slack.com/api/conversations.list") {
                    header("Authorization", "Bearer $accessToken")
                    parameter("types", "public_channel,private_channel")
                    parameter("limit", SLACK_CHANNEL_FETCH_LIMIT)
                }

            val result = json.decodeFromString<SlackChannelsResponse>(response.bodyAsText())
            if (result.ok && result.channels != null) {
                result.channels
            } else {
                logger.error("Failed to list Slack channels: ${result.error}")
                emptyList()
            }
        }.getOrElse { e ->
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
        return suspendRunCatching {
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
        }.getOrElse { e ->
            logger.error("Error listing Slack usergroups", e)
            emptyList()
        }
    }

    suspend fun updateUsergroupMembers(
        accessToken: String,
        usergroupId: String,
        userIds: List<String>
    ): Boolean {
        return suspendRunCatching {
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
        }.getOrElse { e ->
            logger.error("Error updating Slack usergroup members", e)
            false
        }
    }

    // ===== On-Call Notifications =====

    suspend fun sendOnCallAlert(
        userId: Int,
        alertResourceId: String?,
        title: String,
        priority: String
    ) {
        try {
            val orgId = getUserOrganizationId(userId) ?: return
            val slackUserId =
                getSlackUserIdForUser(userId, orgId) ?: run {
                    logger.debug("No Slack mapping found for user $userId")
                    return
                }

            val accessToken = getAccessToken(orgId) ?: return

            val actionsBlock = alertResourceId?.let { publicAlertId ->
                SlackBlock(
                    type = "actions",
                    elements =
                    listOf(
                        SlackElement(
                            type = "button",
                            text = SlackText(type = "plain_text", text = "Acknowledge alert", emoji = false),
                            actionId = "incident_acknowledge_$publicAlertId"
                        ),
                        SlackElement(
                            type = "button",
                            text = SlackText(type = "plain_text", text = "View Details", emoji = false),
                            url = "${EnvConfig.get("FRONTEND_URL", "https://moneat.io")}/on-call/alerts/$publicAlertId",
                            actionId = "incident_view_$publicAlertId"
                        )
                    )
                )
            }

            // Send DM with interactive buttons when this is tied to a real alert.
            val blocks =
                listOfNotNull(
                    SlackBlock(
                        type = "header",
                        text = SlackText(type = "plain_text", text = "🚨 On-Call Alert", emoji = true)
                    ),
                    SlackBlock(
                        type = "section",
                        text = SlackText(type = "mrkdwn", text = "*Priority:* $priority\n*Alert:* $title")
                    ),
                    actionsBlock
                )

            val response =
                httpClient.post("https://slack.com/api/chat.postMessage") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $accessToken")
                    setBody(
                        SlackMessage(
                            channel = slackUserId, // DM to user
                            blocks = blocks,
                            text = "[$priority] $title" // Fallback
                        )
                    )
                }

            val result = json.decodeFromString<SlackPostMessageResponse>(response.bodyAsText())
            if (result.ok) {
                logger.info("Sent on-call Slack DM to user $userId for alert $alertResourceId")
            } else {
                logger.error("Failed to send on-call Slack DM: ${result.error}")
            }
        } catch (e: SerializationException) {
            logger.error(ERROR_SENDING_ON_CALL_SLACK_ALERT, e)
        } catch (e: IOException) {
            logger.error(ERROR_SENDING_ON_CALL_SLACK_ALERT, e)
        } catch (e: IllegalStateException) {
            logger.error(ERROR_SENDING_ON_CALL_SLACK_ALERT, e)
        } catch (e: IllegalArgumentException) {
            logger.error(ERROR_SENDING_ON_CALL_SLACK_ALERT, e)
        }
    }

    private fun getSlackUserIdForUser(
        userId: Int,
        organizationId: Int,
    ): String? {
        val defaultInstallation = installationService.defaultInstallationIdentity(organizationId) ?: return null
        return transaction {
            val mappings = com.moneat.shared.models.SlackUserMappings
                .selectAll()
                .where { com.moneat.shared.models.SlackUserMappings.userId eq userId }
                .toList()
            mappings.firstOrNull {
                it[com.moneat.shared.models.SlackUserMappings.slackInstallationId] == defaultInstallation.internalId
            } ?: defaultInstallation.teamId?.let { teamId ->
                mappings.firstOrNull {
                    it[com.moneat.shared.models.SlackUserMappings.slackInstallationId] == null &&
                        it[com.moneat.shared.models.SlackUserMappings.slackTeamId] == teamId
                }
            }
        }?.get(com.moneat.shared.models.SlackUserMappings.slackUserId)
    }

    private fun getAccessToken(organizationId: Int): String? {
        val installationToken = runCatching {
            installationService.defaultAccessToken(organizationId)
        }.onFailure { error ->
            logger.warn("Unable to read encrypted Slack installation; checking legacy token", error)
        }.getOrNull()
        if (installationToken != null) return installationToken
        return transaction {
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

    companion object {
        private const val ERROR_SENDING_ON_CALL_SLACK_ALERT = "Error sending on-call Slack alert"
        private const val SLACK_SERVER_ERROR_STATUS = 500
        private const val MIN_RATE_LIMIT_RETRY_SECONDS = 1L
        private const val DEFAULT_RATE_LIMIT_RETRY_SECONDS = 30L
    }
}
