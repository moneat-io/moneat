package com.moneat.services.oncall

import com.moneat.config.EnvConfig
import com.moneat.models.UserDeviceTokens
import com.moneat.models.Users
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class PushNotificationService {
    private val logger = LoggerFactory.getLogger(PushNotificationService::class.java)
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }
    
    private val expoAccessToken = EnvConfig.get("EXPO_ACCESS_TOKEN", "")
    private val expoPushEndpoint = "https://exp.host/--/api/v2/push/send"
    
    @Serializable
    data class ExpoMessage(
        val to: String,
        val title: String,
        val body: String,
        val data: Map<String, String> = emptyMap(),
        val sound: String = "default",
        val priority: String = "high",
        val channelId: String = "incidents"
    )
    
    @Serializable
    data class ExpoResponse(
        val data: List<ExpoTicket>? = null
    )
    
    @Serializable
    data class ExpoTicket(
        val status: String,
        val id: String? = null,
        val message: String? = null,
        val details: Map<String, String>? = null
    )
    
    suspend fun sendIncidentAlert(userId: Int, incidentId: Int, title: String, priorityLevel: String) {
        val tokens = getUserDeviceTokens(userId)
        
        if (tokens.isEmpty()) {
            logger.debug("No device tokens found for user $userId")
            return
        }
        
        val messages = tokens.map { token ->
            ExpoMessage(
                to = token,
                title = "[$priorityLevel] $title",
                body = "Tap to view incident details",
                data = mapOf(
                    "type" to "incident",
                    "incidentId" to incidentId.toString(),
                    "priority" to priorityLevel
                )
            )
        }
        
        try {
            val response = httpClient.post(expoPushEndpoint) {
                contentType(ContentType.Application.Json)
                if (expoAccessToken.isNotEmpty()) {
                    header("Authorization", "Bearer $expoAccessToken")
                }
                setBody(messages)
            }
            
            if (response.status.isSuccess()) {
                val result = Json.decodeFromString<ExpoResponse>(response.bodyAsText())
                result.data?.forEachIndexed { index, ticket ->
                    if (ticket.status == "error") {
                        logger.error("Push notification failed for token ${tokens[index]}: ${ticket.message}")
                        
                        // Remove invalid tokens
                        if (ticket.details?.get("error") == "DeviceNotRegistered") {
                            removeDeviceToken(tokens[index])
                        }
                    }
                }
                logger.info("Sent push notification to ${tokens.size} device(s) for user $userId")
            } else {
                logger.error("Push notification request failed: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Failed to send push notification", e)
        }
    }
    
    suspend fun sendPush(userId: Int, title: String, body: String, data: Map<String, String> = emptyMap()) {
        val tokens = getUserDeviceTokens(userId)
        
        if (tokens.isEmpty()) {
            logger.debug("No device tokens found for user $userId")
            return
        }
        
        val messages = tokens.map { token ->
            ExpoMessage(
                to = token,
                title = title,
                body = body,
                data = data
            )
        }
        
        try {
            val response = httpClient.post(expoPushEndpoint) {
                contentType(ContentType.Application.Json)
                if (expoAccessToken.isNotEmpty()) {
                    header("Authorization", "Bearer $expoAccessToken")
                }
                setBody(messages)
            }
            
            if (response.status.isSuccess()) {
                logger.info("Sent push notification to ${tokens.size} device(s) for user $userId")
            } else {
                logger.error("Push notification request failed: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Failed to send push notification", e)
        }
    }
    
    private fun getUserDeviceTokens(userId: Int): List<String> = transaction {
        UserDeviceTokens
            .selectAll()
            .where { UserDeviceTokens.userId eq userId }
            .map { it[UserDeviceTokens.deviceToken] }
    }
    
    fun registerDeviceToken(userId: Int, deviceToken: String, platform: String, deviceName: String?): Boolean = transaction {
        val now = Clock.System.now()
        
        // Check if token already exists
        val existing = UserDeviceTokens
            .selectAll()
            .where { UserDeviceTokens.deviceToken eq deviceToken }
            .singleOrNull()
        
        if (existing != null) {
            // Update last_used_at
            UserDeviceTokens.update({ UserDeviceTokens.deviceToken eq deviceToken }) {
                it[lastUsedAt] = now
            }
            return@transaction true
        }
        
        // Insert new token
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
    
    fun unregisterDeviceToken(deviceToken: String): Boolean = transaction {
        val deleted = UserDeviceTokens.deleteWhere { UserDeviceTokens.deviceToken eq deviceToken }
        logger.info("Unregistered device token: $deviceToken")
        deleted > 0
    }
    
    private fun removeDeviceToken(deviceToken: String) {
        transaction {
            UserDeviceTokens.deleteWhere { UserDeviceTokens.deviceToken eq deviceToken }
        }
    }
    
    fun getUserDevices(userId: Int): List<com.moneat.models.UserDeviceToken> = transaction {
        UserDeviceTokens
            .selectAll()
            .where { UserDeviceTokens.userId eq userId }
            .orderBy(UserDeviceTokens.lastUsedAt to SortOrder.DESC)
            .map { row ->
                com.moneat.models.UserDeviceToken(
                    id = row[UserDeviceTokens.id].value,
                    userId = row[UserDeviceTokens.userId],
                    deviceToken = row[UserDeviceTokens.deviceToken],
                    platform = row[UserDeviceTokens.platform],
                    deviceName = row[UserDeviceTokens.deviceName],
                    createdAt = row[UserDeviceTokens.createdAt].toString(),
                    lastUsedAt = row[UserDeviceTokens.lastUsedAt].toString()
                )
            }
    }
}
