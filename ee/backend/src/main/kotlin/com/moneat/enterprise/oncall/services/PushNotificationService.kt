// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.alerts.models.AlertPriority
import com.moneat.config.EnvConfig
import com.moneat.enterprise.oncall.models.UserDeviceToken
import com.moneat.enterprise.oncall.models.UserDeviceTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Clock

private const val TOKEN_LOG_SUFFIX_LENGTH = 4

class PushNotificationService {
    private val logger = LoggerFactory.getLogger(PushNotificationService::class.java)

    private val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }
        }

    private val expoAccessToken = EnvConfig.get("EXPO_TOKEN", "")
    private val expoPushEndpoint = "https://exp.host/--/api/v2/push/send"

    @Serializable
    data class ExpoMessage(
        val to: String,
        val title: String,
        val body: String,
        val data: Map<String, String> = emptyMap(),
        val sound: String = "default",
        val priority: String = "high",
        val channelId: String = "default",
        val interruptionLevel: String? = null,
    )

    @Serializable
    data class ExpoResponse(
        val data: List<ExpoTicket>? = null,
    )

    @Serializable
    data class ExpoTicket(
        val status: String,
        val id: String? = null,
        val message: String? = null,
        val details: Map<String, String>? = null,
    )

    suspend fun sendOnCallAlert(
        userId: Int,
        alertId: Int,
        title: String,
        priority: String,
        payloadType: String = "alert",
        idKey: String = "alertId",
        body: String = "Tap to view alert details",
    ) {
        val tokens = getUserDeviceTokens(userId)

        if (tokens.isEmpty()) {
            logger.info("No device tokens found for user $userId — push notification skipped for alert $alertId")
            return
        }

        val isCritical = AlertPriority.fromString(priority) in setOf(AlertPriority.P0, AlertPriority.P1)
        val channelId = if (isCritical) "critical" else "default"
        val interruptionLevel = if (isCritical) "critical" else null

        val messages =
            tokens.map { token ->
                ExpoMessage(
                    to = token,
                    title = "[$priority] $title",
                    body = body,
                    data =
                        mapOf(
                            "type" to payloadType,
                            idKey to alertId.toString(),
                            "priority" to priority,
                        ),
                    channelId = channelId,
                    interruptionLevel = interruptionLevel,
                )
            }

        try {
            val response =
                httpClient.post(expoPushEndpoint) {
                    contentType(ContentType.Application.Json)
                    if (expoAccessToken.isNotEmpty()) {
                        header("Authorization", "Bearer $expoAccessToken")
                    }
                    setBody(messages)
                }

            handleExpoResponse(userId, tokens, response)
        } catch (e: Exception) {
            logger.error("Failed to send push notification", e)
        }
    }

    private suspend fun handleExpoResponse(
        userId: Int,
        tokens: List<String>,
        response: HttpResponse,
    ) {
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            logger.error("Push request failed HTTP ${response.status}: $body")
            return
        }

        val result = Json.decodeFromString<ExpoResponse>(body)
        result.data?.forEachIndexed { index, ticket ->
            handleExpoTicket(userId, tokens[index], ticket)
        }
    }

    private fun handleExpoTicket(
        userId: Int,
        token: String,
        ticket: ExpoTicket,
    ) {
        if (ticket.status == "error") {
            logger.error(
                "Push failed for token ${redactToken(token)}: ${ticket.message} (details: ${ticket.details})",
            )
            if (ticket.details?.get("error") == "DeviceNotRegistered") {
                removeDeviceToken(token)
            }
            return
        }

        logger.info("Push ticket ok for user $userId, ticketId=${ticket.id}, token=${redactToken(token)}")
    }

    suspend fun sendIncidentAlert(
        userId: Int,
        incidentId: Int,
        title: String,
        priority: String,
    ) {
        sendOnCallAlert(
            userId = userId,
            alertId = incidentId,
            title = title,
            priority = priority,
            payloadType = "incident",
            idKey = "incidentId",
            body = "Tap to view incident details",
        )
    }

    private fun redactToken(token: String): String {
        if (token.length <= TOKEN_LOG_SUFFIX_LENGTH) return "****"
        return "****${token.takeLast(TOKEN_LOG_SUFFIX_LENGTH)}"
    }

    suspend fun sendOnCallAssignmentAlert(
        userId: Int,
        scheduleName: String,
    ) {
        val tokens = getUserDeviceTokens(userId)

        if (tokens.isEmpty()) {
            logger.debug("No device tokens found for user $userId")
            return
        }

        val messages =
            tokens.map { token ->
                ExpoMessage(
                    to = token,
                    title = "You are now on-call",
                    body = "You are now the primary on-call for $scheduleName",
                    data =
                        mapOf(
                            "type" to "on_call_assignment",
                            "scheduleName" to scheduleName,
                        ),
                    priority = "high",
                    channelId = "critical",
                    interruptionLevel = "critical",
                )
            }

        try {
            val response =
                httpClient.post(expoPushEndpoint) {
                    contentType(ContentType.Application.Json)
                    if (expoAccessToken.isNotEmpty()) {
                        header("Authorization", "Bearer $expoAccessToken")
                    }
                    setBody(messages)
                }

            if (response.status.value in 200..299) {
                logger.info("Sent on-call assignment alert to ${tokens.size} device(s) for user $userId")
            } else {
                logger.error("Push notification request failed: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Failed to send push notification", e)
        }
    }

    suspend fun sendPush(
        userId: Int,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ) {
        val tokens = getUserDeviceTokens(userId)

        if (tokens.isEmpty()) {
            logger.debug("No device tokens found for user $userId")
            return
        }

        val messages =
            tokens.map { token ->
                ExpoMessage(
                    to = token,
                    title = title,
                    body = body,
                    data = data,
                )
            }

        try {
            val response =
                httpClient.post(expoPushEndpoint) {
                    contentType(ContentType.Application.Json)
                    if (expoAccessToken.isNotEmpty()) {
                        header("Authorization", "Bearer $expoAccessToken")
                    }
                    setBody(messages)
                }

            if (response.status.value in 200..299) {
                logger.info("Sent push notification to ${tokens.size} device(s) for user $userId")
            } else {
                logger.error("Push notification request failed: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Failed to send push notification", e)
        }
        }

    private fun getUserDeviceTokens(userId: Int): List<String> =
        transaction {
            UserDeviceTokens
                .selectAll()
                .where { UserDeviceTokens.userId eq userId }
                .map { it[UserDeviceTokens.deviceToken] }
        }

    fun registerDeviceToken(
        userId: Int,
        deviceToken: String,
        platform: String,
        deviceName: String?,
    ): Boolean =
        transaction {
            val now = Clock.System.now()

            val existing =
                UserDeviceTokens
                    .selectAll()
                    .where { UserDeviceTokens.deviceToken eq deviceToken }
                    .singleOrNull()

            if (existing != null) {
                // Rebind ownership and refresh metadata when token is re-registered.
                UserDeviceTokens.update({ UserDeviceTokens.deviceToken eq deviceToken }) {
                    it[UserDeviceTokens.userId] = userId
                    it[UserDeviceTokens.platform] = platform
                    it[UserDeviceTokens.deviceName] = deviceName
                    it[lastUsedAt] = now
                }
                logger.info("Rebound existing device token to user $userId, platform $platform")
                return@transaction true
            }

            UserDeviceTokens.insert {
                it[UserDeviceTokens.userId] = userId
                it[UserDeviceTokens.deviceToken] = deviceToken
                it[UserDeviceTokens.platform] = platform
                it[UserDeviceTokens.deviceName] = deviceName
                it[createdAt] = now
                it[lastUsedAt] = now
            }

            logger.info("Registered device token for user $userId, platform $platform")
            true
        }

    fun unregisterDeviceToken(
        userId: Int,
        deviceToken: String,
    ): Boolean =
        transaction {
            val deleted =
                UserDeviceTokens.deleteWhere {
                    (UserDeviceTokens.userId eq userId) and (UserDeviceTokens.deviceToken eq deviceToken)
                }
            logger.info("Unregistered device token: $deviceToken")
            deleted > 0
        }

    private fun removeDeviceToken(deviceToken: String) {
        transaction {
            UserDeviceTokens.deleteWhere { UserDeviceTokens.deviceToken eq deviceToken }
        }
    }

    fun getUserDevices(userId: Int): List<UserDeviceToken> =
        transaction {
            UserDeviceTokens
                .selectAll()
                .where { UserDeviceTokens.userId eq userId }
                .orderBy(UserDeviceTokens.lastUsedAt to SortOrder.DESC)
                .map { row ->
                    UserDeviceToken(
                        id = row[UserDeviceTokens.id].value,
                        userId = row[UserDeviceTokens.userId],
                        deviceToken = row[UserDeviceTokens.deviceToken],
                        platform = row[UserDeviceTokens.platform],
                        deviceName = row[UserDeviceTokens.deviceName],
                        createdAt = row[UserDeviceTokens.createdAt].toString(),
                        lastUsedAt = row[UserDeviceTokens.lastUsedAt].toString(),
                    )
                }
        }
}
