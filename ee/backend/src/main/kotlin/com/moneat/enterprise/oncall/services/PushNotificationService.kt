// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.alerts.models.AlertPriority
import com.moneat.config.EnvConfig
import com.moneat.enterprise.oncall.alertResourceId
import com.moneat.enterprise.oncall.models.UserDeviceToken
import com.moneat.enterprise.oncall.models.UserDeviceTokens
import com.moneat.enterprise.oncall.userResourceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import kotlin.time.Duration.Companion.minutes

private const val TOKEN_LOG_SUFFIX_LENGTH = 4
private const val IOS_PLATFORM = "IOS"
private const val INCIDENT_CATEGORY_ID = "INCIDENT_ALERT"
private const val DEVICE_NOT_REGISTERED_ERROR = "DeviceNotRegistered"
private const val EXPO_PUSH_ENDPOINT = "https://exp.host/--/api/v2/push/send"
private const val EXPO_RECEIPTS_ENDPOINT = "https://exp.host/--/api/v2/push/getReceipts"
private const val EXPO_REQUEST_TIMEOUT_MILLIS = 10_000L
private const val RECEIPT_CHECK_ATTEMPTS = 3
private val RECEIPT_INITIAL_DELAY = 15.minutes
private val RECEIPT_RETRY_DELAY = 5.minutes
private val DEFAULT_SOUND = JsonPrimitive("default")
private val IOS_CRITICAL_SOUND =
    buildJsonObject {
        put("critical", true)
        put("name", "default")
        put("volume", 1.0)
    }
private val EXPO_JSON =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

internal data class OnCallMessageContent(
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val isCritical: Boolean,
    val categoryId: String?,
)

internal fun expoSound(
    platform: String,
    isCritical: Boolean,
): JsonElement =
    if (isCritical && platform.uppercase() == IOS_PLATFORM) {
        IOS_CRITICAL_SOUND
    } else {
        DEFAULT_SOUND
    }

internal fun buildOnCallMessage(
    device: PushNotificationService.RegisteredDevice,
    content: OnCallMessageContent,
): PushNotificationService.ExpoMessage =
    PushNotificationService.ExpoMessage(
        to = device.token,
        title = content.title,
        body = content.body,
        data = content.data,
        sound = expoSound(device.platform, content.isCritical),
        channelId = if (content.isCritical) "critical" else "default",
        interruptionLevel = if (content.isCritical) "critical" else null,
        categoryId = content.categoryId,
    )

class PushNotificationService {
    private val logger = LoggerFactory.getLogger(PushNotificationService::class.java)
    private val receiptScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineName("expo-push-receipts"),
        )

    private val httpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = EXPO_REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = EXPO_REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = EXPO_REQUEST_TIMEOUT_MILLIS
            }
            install(ContentNegotiation) {
                json(
                    EXPO_JSON,
                )
            }
        }

    private val expoAccessToken = EnvConfig.get("EXPO_TOKEN", "")

    @Serializable
    data class ExpoMessage(
        val to: String,
        val title: String,
        val body: String,
        val data: Map<String, String> = emptyMap(),
        val sound: JsonElement = DEFAULT_SOUND,
        val priority: String = "high",
        val channelId: String = "default",
        val interruptionLevel: String? = null,
        val categoryId: String? = null,
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

    @Serializable
    data class ExpoReceiptRequest(
        val ids: List<String>,
    )

    @Serializable
    data class ExpoReceiptResponse(
        val data: Map<String, ExpoReceipt>? = null,
    )

    @Serializable
    data class ExpoReceipt(
        val status: String,
        val message: String? = null,
        val details: Map<String, String>? = null,
    )

    internal data class RegisteredDevice(
        val token: String,
        val platform: String,
    )

    private data class PendingReceipt(
        val ticketId: String,
        val token: String,
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
        val devices = getUserDevicesForPush(userId)

        if (devices.isEmpty()) {
            logger.info("No device tokens found for user $userId - push notification skipped for alert $alertId")
            return
        }

        val alertResourceId = transaction { alertResourceId(alertId) }
        val isCritical = AlertPriority.fromString(priority) in setOf(AlertPriority.P0, AlertPriority.P1)

        val messages =
            devices.map { device ->
                buildOnCallMessage(
                    device = device,
                    content =
                        OnCallMessageContent(
                            title = "[$priority] $title",
                            body = body,
                            data =
                                mapOf(
                                    "type" to payloadType,
                                    idKey to alertResourceId,
                                    "priority" to priority,
                                ),
                            isCritical = isCritical,
                            categoryId = INCIDENT_CATEGORY_ID.takeIf { payloadType == "incident" },
                        ),
                )
            }

        postMessages(userId, devices, messages)
    }

    private suspend fun handleExpoResponse(
        userId: Int,
        devices: List<RegisteredDevice>,
        response: HttpResponse,
    ) {
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            logger.error("Push request failed HTTP ${response.status}: $body")
            return
        }

        val result = EXPO_JSON.decodeFromString<ExpoResponse>(body)
        val pendingReceipts =
            result.data.orEmpty().mapIndexedNotNull { index, ticket ->
                val device = devices.getOrNull(index)
                if (device == null) {
                    logger.warn("Expo returned more push tickets than submitted messages")
                    return@mapIndexedNotNull null
                }
                handleExpoTicket(userId, device.token, ticket)
            }
        if (pendingReceipts.isNotEmpty()) {
            scheduleReceiptChecks(userId, pendingReceipts)
        }
    }

    private fun handleExpoTicket(
        userId: Int,
        token: String,
        ticket: ExpoTicket,
    ): PendingReceipt? {
        if (ticket.status == "error") {
            logger.error(
                "Push failed for token ${redactToken(token)}: ${ticket.message} (details: ${ticket.details})",
            )
            if (ticket.details?.get("error") == DEVICE_NOT_REGISTERED_ERROR) {
                removeDeviceToken(token)
            }
            return null
        }

        val ticketId = ticket.id
        if (ticketId == null) {
            logger.warn("Expo accepted a push for user $userId without returning a receipt ticket")
            return null
        }
        logger.info("Push ticket ok for user $userId, ticketId=${ticket.id}, token=${redactToken(token)}")
        return PendingReceipt(ticketId, token)
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
        val devices = getUserDevicesForPush(userId)

        if (devices.isEmpty()) {
            logger.debug("No device tokens found for user $userId")
            return
        }

        val messages =
            devices.map { device ->
                ExpoMessage(
                    to = device.token,
                    title = "You are now on-call",
                    body = "You are now the primary on-call for $scheduleName",
                    data =
                        mapOf(
                            "type" to "on_call_assignment",
                            "scheduleName" to scheduleName,
                        ),
                    sound = expoSound(device.platform, isCritical = true),
                    priority = "high",
                    channelId = "critical",
                    interruptionLevel = "critical",
                )
            }

        postMessages(userId, devices, messages)
    }

    suspend fun sendPush(
        userId: Int,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ) {
        val devices = getUserDevicesForPush(userId)

        if (devices.isEmpty()) {
            logger.debug("No device tokens found for user $userId")
            return
        }

        val messages =
            devices.map { device ->
                ExpoMessage(
                    to = device.token,
                    title = title,
                    body = body,
                    data = data,
                )
            }

        postMessages(userId, devices, messages)
    }

    private suspend fun postMessages(
        userId: Int,
        devices: List<RegisteredDevice>,
        messages: List<ExpoMessage>,
    ) {
        try {
            val response =
                httpClient.post(EXPO_PUSH_ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    addExpoAuthorization()
                    setBody(messages)
                }

            handleExpoResponse(userId, devices, response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to send push notification", e)
        }
    }

    private fun scheduleReceiptChecks(
        userId: Int,
        receipts: List<PendingReceipt>,
    ) {
        receiptScope.launch {
            delay(RECEIPT_INITIAL_DELAY)
            checkReceipts(userId, receipts)
        }
    }

    private suspend fun checkReceipts(
        userId: Int,
        receipts: List<PendingReceipt>,
    ) {
        var pending = receipts.associateBy { it.ticketId }
        repeat(RECEIPT_CHECK_ATTEMPTS) { attempt ->
            pending = fetchReceipts(userId, pending)
            if (pending.isEmpty()) {
                return
            }
            if (attempt < RECEIPT_CHECK_ATTEMPTS - 1) {
                delay(RECEIPT_RETRY_DELAY)
            }
        }
        logger.warn(
            "Expo receipts remained unavailable for user $userId after $RECEIPT_CHECK_ATTEMPTS attempts: " +
                pending.keys.joinToString(),
        )
    }

    private suspend fun fetchReceipts(
        userId: Int,
        pending: Map<String, PendingReceipt>,
    ): Map<String, PendingReceipt> =
        try {
            val response =
                httpClient.post(EXPO_RECEIPTS_ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    addExpoAuthorization()
                    setBody(ExpoReceiptRequest(pending.keys.toList()))
                }
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                logger.error("Expo receipt request failed HTTP ${response.status}: $body")
                pending
            } else {
                val result = EXPO_JSON.decodeFromString<ExpoReceiptResponse>(body)
                result.data.orEmpty().forEach { (ticketId, receipt) ->
                    pending[ticketId]?.let { handleExpoReceipt(userId, it, receipt) }
                }
                pending - result.data.orEmpty().keys
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to fetch Expo push receipts for user $userId", e)
            pending
        }

    private fun handleExpoReceipt(
        userId: Int,
        pending: PendingReceipt,
        receipt: ExpoReceipt,
    ) {
        if (receipt.status == "ok") {
            logger.info(
                "Push receipt ok for user $userId, ticketId=${pending.ticketId}",
            )
            return
        }

        logger.error(
            "Push receipt failed for user $userId, ticketId=${pending.ticketId}, " +
                "message=${receipt.message} (details: ${receipt.details})",
        )
        if (receipt.details?.get("error") == DEVICE_NOT_REGISTERED_ERROR) {
            removeDeviceToken(pending.token)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addExpoAuthorization() {
        if (expoAccessToken.isNotEmpty()) {
            header("Authorization", "Bearer $expoAccessToken")
        }
    }

    private fun getUserDevicesForPush(userId: Int): List<RegisteredDevice> =
        transaction {
            UserDeviceTokens
                .selectAll()
                .where { UserDeviceTokens.userId eq userId }
                .map {
                    RegisteredDevice(
                        token = it[UserDeviceTokens.deviceToken],
                        platform = it[UserDeviceTokens.platform],
                    )
                }
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
            val rows = UserDeviceTokens
                .selectAll()
                .where { UserDeviceTokens.userId eq userId }
                .orderBy(UserDeviceTokens.lastUsedAt to SortOrder.DESC)
                .toList()
            if (rows.isEmpty()) {
                emptyList()
            } else {
                val userResourceId = userResourceId(userId)
                rows.map { row ->
                    UserDeviceToken(
                        id = row[UserDeviceTokens.resourceId].toString(),
                        userResourceId = userResourceId,
                        deviceToken = row[UserDeviceTokens.deviceToken],
                        platform = row[UserDeviceTokens.platform],
                        deviceName = row[UserDeviceTokens.deviceName],
                        createdAt = row[UserDeviceTokens.createdAt].toString(),
                        lastUsedAt = row[UserDeviceTokens.lastUsedAt].toString(),
                        internalId = row[UserDeviceTokens.id].value,
                        userId = row[UserDeviceTokens.userId],
                    )
                }
            }
        }
}
