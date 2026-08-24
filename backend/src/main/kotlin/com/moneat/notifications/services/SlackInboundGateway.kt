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
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.shared.models.SlackInboundDeliveries
import com.moneat.shared.models.SlackInboundDeliveryStatus
import io.ktor.http.Headers
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val SIGNATURE_VERSION = "v0"
private const val SIGNATURE_HEADER = "X-Slack-Signature"
private const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
private const val REQUEST_ID_HEADER = "X-Slack-Request-ID"
private const val MAX_REQUEST_AGE_SECONDS = 300L
private const val MAX_BODY_CHARS = 1_000_000
private const val SHA256_HEX_LENGTH = 64
private const val DEFAULT_QUEUE_KEY = "slack-inbound"
private const val EVENT_URL_VERIFICATION = "url_verification"
private const val EVENT_ID_FIELD = "event_id"
private const val TYPE_FIELD = "type"
private const val PAYLOAD_FIELD = "payload"

private val json = Json { ignoreUnknownKeys = true }
private val logger = LoggerFactory.getLogger(SlackInboundGateway::class.java)

enum class SlackInboundRequestType(val wire: String) {
    COMMAND("commands"),
    EVENT("events"),
    SHORTCUT("shortcuts"),
    MENTION("mentions"),
    INTERACTION("interactions"),
}

@Serializable
data class SlackInboundAcceptance(
    val deliveryId: String?,
    val duplicate: Boolean,
    val requestType: String,
    val challenge: String? = null,
)

class SlackInboundRequestException(
    val reason: SlackInboundRequestRejection,
    override val message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

enum class SlackInboundRequestRejection {
    INVALID_SIGNATURE,
    INVALID_BODY,
    QUEUE_UNAVAILABLE,
}

data class SlackInboundContext(
    val deliveryKey: String,
    val requestType: SlackInboundRequestType,
    val payload: String,
    val teamId: String?,
    val enterpriseId: String?,
    val channelId: String?,
    val userId: String?,
    val messageTs: String?,
    val threadTs: String?,
    val viewId: String?,
    val challenge: String?,
)

private data class ParsedSlackPayload(
    val root: JsonObject,
    val form: Parameters,
)

private data class SlackContextIdentifiers(
    val teamId: String?,
    val enterpriseId: String?,
    val channelId: String?,
    val userId: String?,
    val messageTs: String?,
    val threadTs: String?,
    val viewId: String?,
)

/**
 * Authenticates and durably admits Slack deliveries before any organization lookup.
 * The persisted row is the audit/context record; the Redis stream is only the
 * asynchronous wake-up for the worker that completes ingress processing.
 */
class SlackInboundGateway(
    private val queueKey: String = EnvConfig.get("SLACK_INBOUND_QUEUE_KEY") ?: DEFAULT_QUEUE_KEY,
    private val signingSecret: String? = EnvConfig.get("SLACK_SIGNING_SECRET")?.takeIf { it.isNotBlank() },
) {
    fun accept(
        headers: Headers,
        rawBody: String,
        requestType: SlackInboundRequestType,
    ): SlackInboundAcceptance {
        val context = authenticateAndParse(headers, rawBody, requestType)
        if (context.challenge != null) {
            return SlackInboundAcceptance(
                deliveryId = null,
                duplicate = false,
                requestType = requestType.wire,
                challenge = context.challenge,
            )
        }
        val existing = findOrInsert(context)
        if (!existing.inserted && existing.status != SlackInboundDeliveryStatus.RETRY.wire) {
            return SlackInboundAcceptance(
                deliveryId = existing.resourceId,
                duplicate = true,
                requestType = requestType.wire,
                challenge = context.challenge,
            )
        }

        val resourceId = existing.resourceId
            ?: throw SlackInboundRequestException(
                SlackInboundRequestRejection.INVALID_BODY,
                "Slack delivery could not be persisted",
            )
        try {
            IngestionQueueClient.enqueue(IngestionPipeline.SLACK_INBOUND, queueKey, resourceId)
            markQueued(resourceId)
        } catch (error: Exception) {
            markRetry(resourceId, error.message)
            throw SlackInboundRequestException(
                SlackInboundRequestRejection.QUEUE_UNAVAILABLE,
                "Slack delivery queue is temporarily unavailable",
                error,
            )
        }

        return SlackInboundAcceptance(
            deliveryId = resourceId,
            duplicate = false,
            requestType = requestType.wire,
            challenge = context.challenge,
        )
    }

    /** Completes ingress processing for a queued delivery. Domain consumers can read the durable row. */
    fun process(resourceId: String) {
        val parsedId = resourceId.toUuidOrNull()
            ?: throw IllegalArgumentException("Invalid Slack delivery resource ID")
        val claimed = transaction {
            val row = SlackInboundDeliveries
                .selectAll()
                .where { SlackInboundDeliveries.resourceId eq parsedId }
                .firstOrNull()
                ?: return@transaction false
            if (row[SlackInboundDeliveries.status] == SlackInboundDeliveryStatus.PROCESSED.wire) {
                return@transaction true
            }
            SlackInboundDeliveries.update({ SlackInboundDeliveries.resourceId eq parsedId }) {
                it[status] = SlackInboundDeliveryStatus.PROCESSING.wire
                it[attemptCount] = row[SlackInboundDeliveries.attemptCount] + 1
                it[leasedAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
            true
        }
        if (!claimed) return

        transaction {
            SlackInboundDeliveries.update({ SlackInboundDeliveries.resourceId eq parsedId }) {
                it[status] = SlackInboundDeliveryStatus.PROCESSED.wire
                it[processedAt] = Clock.System.now()
                it[leasedAt] = null
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    private fun authenticateAndParse(
        headers: Headers,
        rawBody: String,
        requestType: SlackInboundRequestType,
    ): SlackInboundContext {
        if (rawBody.length > MAX_BODY_CHARS) {
            throw SlackInboundRequestException(
                SlackInboundRequestRejection.INVALID_BODY,
                "Slack request body is too large",
            )
        }
        if (!verifySlackSignature(headers, rawBody, currentTimestampSeconds(), signingSecret)) {
            throw SlackInboundRequestException(
                SlackInboundRequestRejection.INVALID_SIGNATURE,
                "Invalid Slack signature",
            )
        }

        return try {
            parseContext(headers, rawBody, requestType)
        } catch (error: Exception) {
            throw SlackInboundRequestException(
                SlackInboundRequestRejection.INVALID_BODY,
                "Invalid Slack request body",
                error,
            )
        }
    }

    private fun parseContext(
        headers: Headers,
        rawBody: String,
        requestType: SlackInboundRequestType,
    ): SlackInboundContext {
        val parsed = parsePayload(rawBody)
        val root = parsed.root
        val event = eventObject(root)
        val identifiers = parseIdentifiers(root, event, parsed.form)
        val identity = deliveryIdentity(root, parsed.form, identifiers.viewId, headers, rawBody)

        return SlackInboundContext(
            deliveryKey = "${identifiers.teamId ?: "unknown"}:${requestType.wire}:$identity"
                .take(MAX_DELIVERY_KEY_CHARS),
            requestType = requestType,
            payload = rawBody,
            teamId = identifiers.teamId,
            enterpriseId = identifiers.enterpriseId,
            channelId = identifiers.channelId,
            userId = identifiers.userId,
            messageTs = identifiers.messageTs,
            threadTs = identifiers.threadTs,
            viewId = identifiers.viewId,
            challenge = challenge(root),
        )
    }

    private fun parsePayload(rawBody: String): ParsedSlackPayload {
        val form = parseQueryString(rawBody)
        val root = form[PAYLOAD_FIELD]?.let(::parseJsonObject)
            ?: rawBody.trimStart().takeIf { it.startsWith("{") }?.let(::parseJsonObject)
            ?: JsonObject(emptyMap())
        return ParsedSlackPayload(root, form)
    }

    private fun eventObject(root: JsonObject): JsonObject = root["event"]?.jsonObject ?: root

    private fun parseIdentifiers(
        root: JsonObject,
        event: JsonObject,
        form: Parameters,
    ): SlackContextIdentifiers {
        val container = root["container"]?.jsonObject
        val view = root["view"]?.jsonObject
        return SlackContextIdentifiers(
            teamId = firstValue(value(root, "team", "id"), value(event, "team_id"), form["team_id"]),
            enterpriseId = firstValue(value(root, "enterprise", "id"), form["enterprise_id"]),
            channelId = firstValue(value(root, "channel", "id"), value(event, "channel_id"), form["channel_id"]),
            userId = firstValue(value(root, "user", "id"), value(event, "user_id"), form["user_id"]),
            messageTs = firstValue(value(container, "message_ts"), value(event, "message_ts"), form["message_ts"]),
            threadTs = firstValue(value(container, "thread_ts"), value(event, "thread_ts"), form["thread_ts"]),
            viewId = firstValue(value(view, "id"), form["view_id"]),
        )
    }

    private fun deliveryIdentity(
        root: JsonObject,
        form: Parameters,
        viewId: String?,
        headers: Headers,
        rawBody: String,
    ): String {
        val eventId = firstValue(value(root, EVENT_ID_FIELD), form[EVENT_ID_FIELD])
        val callbackId = firstValue(value(root, "trigger_id"), value(root, "action_ts"), viewId, form["trigger_id"])
        return firstValue(eventId, callbackId, headers[REQUEST_ID_HEADER]) ?: sha256(rawBody)
    }

    private fun challenge(root: JsonObject): String? =
        if (value(root, TYPE_FIELD) == EVENT_URL_VERIFICATION) value(root, "challenge") else null

    private fun findOrInsert(context: SlackInboundContext): ExistingDelivery = transaction {
        val existing = SlackInboundDeliveries
            .selectAll()
            .where { SlackInboundDeliveries.deliveryKey eq context.deliveryKey }
            .firstOrNull()
        if (existing != null) {
            return@transaction ExistingDelivery(
                resourceId = existing[SlackInboundDeliveries.resourceId].toString(),
                status = existing[SlackInboundDeliveries.status],
                inserted = false,
            )
        }

        SlackInboundDeliveries.insertIgnore {
            it[deliveryKey] = context.deliveryKey
            it[requestType] = context.requestType.wire
            it[payload] = context.payload
            it[teamId] = context.teamId
            it[enterpriseId] = context.enterpriseId
            it[channelId] = context.channelId
            it[userId] = context.userId
            it[messageTs] = context.messageTs
            it[threadTs] = context.threadTs
            it[viewId] = context.viewId
            it[status] = SlackInboundDeliveryStatus.PENDING.wire
        }
        val inserted = SlackInboundDeliveries
            .selectAll()
            .where { SlackInboundDeliveries.deliveryKey eq context.deliveryKey }
            .single()
        ExistingDelivery(
            resourceId = inserted[SlackInboundDeliveries.resourceId].toString(),
            status = inserted[SlackInboundDeliveries.status],
            inserted = true,
        )
    }

    private fun markQueued(resourceId: String) {
        val id = resourceId.toUuidOrNull() ?: return
        transaction {
            SlackInboundDeliveries.update({
                (SlackInboundDeliveries.resourceId eq id) and
                    (SlackInboundDeliveries.status eq SlackInboundDeliveryStatus.PENDING.wire)
            }) {
                it[status] = SlackInboundDeliveryStatus.QUEUED.wire
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    private fun markRetry(resourceId: String, error: String?) {
        val id = resourceId.toUuidOrNull() ?: return
        transaction {
            SlackInboundDeliveries.update({ SlackInboundDeliveries.resourceId eq id }) {
                it[status] = SlackInboundDeliveryStatus.RETRY.wire
                it[lastError] = error?.take(MAX_ERROR_CHARS)
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    private data class ExistingDelivery(
        val resourceId: String?,
        val status: String,
        val inserted: Boolean,
    )

    companion object {
        private const val MAX_DELIVERY_KEY_CHARS = 384
        private const val MAX_ERROR_CHARS = 1_000

        fun verifySlackSignature(
            headers: Headers,
            rawBody: String,
            nowSeconds: Long,
            signingSecret: String? = null,
        ): Boolean {
            val timestamp = headers[TIMESTAMP_HEADER] ?: return false
            val signature = headers[SIGNATURE_HEADER] ?: return false
            val timestampSeconds = timestamp.toLongOrNull() ?: return false
            if (abs(nowSeconds - timestampSeconds) > MAX_REQUEST_AGE_SECONDS) return false
            val secret = signingSecret?.takeIf { it.isNotBlank() }
                ?: EnvConfig.get("SLACK_SIGNING_SECRET")?.takeIf { it.isNotBlank() }
                ?: return false
            return verifySlackSignature(secret, timestamp, signature, rawBody, nowSeconds)
        }

        fun verifySlackSignature(
            secret: String,
            timestamp: String,
            signature: String,
            rawBody: String,
            nowSeconds: Long = timestamp.toLongOrNull() ?: Long.MIN_VALUE,
        ): Boolean {
            if (!signature.startsWith("$SIGNATURE_VERSION=")) return false
            val timestampSeconds = timestamp.toLongOrNull() ?: return false
            if (abs(nowSeconds - timestampSeconds) > MAX_REQUEST_AGE_SECONDS) return false
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val digest = mac.doFinal("$SIGNATURE_VERSION:$timestamp:$rawBody".toByteArray(Charsets.UTF_8))
            val expected = "$SIGNATURE_VERSION=" + digest.joinToString("") { "%02x".format(it) }
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                signature.toByteArray(Charsets.UTF_8),
            )
        }

        private fun currentTimestampSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(SHA256_HEX_LENGTH)
        }

        private fun parseJsonObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

        private fun value(element: JsonObject?, key: String): String? =
            element?.get(key)?.jsonPrimitive?.contentOrNull

        private fun value(element: JsonObject?, parent: String, key: String): String? =
            element?.get(parent)?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull

        private fun firstValue(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

        private const val MILLIS_PER_SECOND = 1000L
    }
}

private fun String.toUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }.getOrNull()
