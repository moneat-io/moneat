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

package com.moneat.events.services

import com.moneat.config.EnvConfig
import com.moneat.events.models.EnvelopeItem
import com.moneat.events.models.ExceptionInfo
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.models.SentryEvent
import com.moneat.events.models.SentryFeedback
import com.moneat.events.models.SentryReplayEvent
import com.moneat.events.models.SentrySpan
import com.moneat.events.models.SentryTransaction
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.models.ErrorEventInsertData
import com.moneat.events.repositories.models.FeedbackInsertData
import com.moneat.events.repositories.models.LlmGenerationInsertData
import com.moneat.events.repositories.models.ProfileInsertData
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.ReplayRecordingInsertData
import com.moneat.events.repositories.models.TransactionEventInsertData
import com.moneat.notifications.services.NotificationService
import com.moneat.otlp.hexToULongPair
import com.moneat.config.ClickHouseClient
import com.moneat.shared.services.CacheService
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import com.moneat.utils.ClickHouseSqlUtils.mapToSqlMap
import com.moneat.utils.ClickHouseSqlUtils.doubleMapToSqlMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

class EventService(
    private val notificationService: NotificationService? = null,
    private val eventRepository: EventRepository,
    private val releaseService: ReleaseService = ReleaseService(),
) {
    companion object {
        private const val DEFAULT_PROFILE_MAX_PAYLOAD_BYTES = 10 * 1024 * 1024 // 10 MiB
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracker = UsageTrackingService.instance
    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val profileStoragePath: String = EnvConfig.get(
        "PROFILE_STORAGE_PATH",
        "/var/lib/moneat/profiles"
    )
    private val maxProfilePayloadBytes: Int =
        EnvConfig
            .get(
                "PROFILE_MAX_PAYLOAD_BYTES",
                DEFAULT_PROFILE_MAX_PAYLOAD_BYTES.toString()
            ).toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_PROFILE_MAX_PAYLOAD_BYTES

    // In-memory caches for hot-path lookups (project keys & org IDs rarely change)
    private data class CachedEntry<T>(val value: T, val expiresAt: Long)

    private val projectKeyCache = ConcurrentHashMap<String, CachedEntry<ProjectKeyVerification>>()
    private val orgIdCache = ConcurrentHashMap<Long, CachedEntry<Int?>>()
    private val knownIssueIds = ConcurrentHashMap.newKeySet<String>()
    private val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private val MAX_KNOWN_ISSUES = 100_000

    // Track replay segment counters for mobile replays that lack a separate replay_event item.
    // Maps replay_id -> next segment counter. Cleaned up when map exceeds threshold.
    private val replaySegmentCounters = ConcurrentHashMap<String, AtomicInteger>()

    fun verifyProjectKey(
        projectId: Long,
        publicKey: String
    ): ProjectKeyVerification {
        val cacheKey = "$projectId:$publicKey"
        val now = System.currentTimeMillis()
        projectKeyCache[cacheKey]?.let { if (it.expiresAt > now) return it.value }

        val result = eventRepository.verifyProjectKey(projectId, publicKey)
        projectKeyCache[cacheKey] = CachedEntry(result, now + CACHE_TTL_MS)
        return result
    }

    fun getOrganizationIdForProject(projectId: Long): Int? {
        val now = System.currentTimeMillis()
        orgIdCache[projectId]?.let { if (it.expiresAt > now) return it.value }

        val result = eventRepository.getOrganizationIdForProject(projectId)
        orgIdCache[projectId] = CachedEntry(result, now + CACHE_TTL_MS)
        return result
    }

    suspend fun processEnvelope(
        projectId: Long,
        envelope: SentryEnvelope
    ) {
        var lastReplayId: String? = null
        var lastSegmentId: Int = 0
        for (item in envelope.items) {
            logger.debug { "Processing envelope item type: ${item.type}" }
            when (item.type) {
                "event" -> {
                    logger.debug { "Event payload: ${item.payload.take(500)}" }
                    val event = json.decodeFromString<SentryEvent>(item.payload)
                    if (storeEvent(projectId, event)) {
                        recordUsage(projectId, "error", item)
                    }
                }

                "transaction" -> {
                    logger.debug { "Transaction payload: ${item.payload.take(500)}" }
                    val transaction = parseTransactionPayload(item.payload)
                    if (storeTransaction(projectId, transaction)) {
                        recordUsage(projectId, "transaction", item)
                    }
                }

                "session" -> {
                    // TODO: Handle sessions
                    logger.debug { "Received session (not yet implemented)" }
                }

                "replay_event" -> {
                    val replayEvent = parseReplayEventPayload(item.payload)
                    lastReplayId = replayEvent.replay_id
                    lastSegmentId = replayEvent.segment_id ?: 0
                    if (storeReplayEvent(projectId, replayEvent)) {
                        recordUsage(projectId, "replay", item)
                    }
                }

                "replay_recording" -> {
                    val rid = lastReplayId ?: envelope.eventId
                    storeReplayRecording(projectId, rid, lastSegmentId, item.payload)
                    recordUsage(projectId, "replay", item)
                    lastReplayId = null
                    lastSegmentId = 0
                }

                "replay_video" -> {
                    // Mobile replay uses replay_video instead of replay_recording.
                    // The SDK may send a preceding replay_event item (with replay_id & segment_id)
                    // or just a standalone replay_video. Handle both cases.
                    val rid = lastReplayId ?: envelope.eventId

                    val segmentId =
                        if (lastReplayId != null) {
                            // A replay_event was parsed in this envelope - use its segment_id
                            lastSegmentId
                        } else {
                            // No replay_event - derive segment_id from an in-memory counter.
                            // Evict stale entries if the map grows too large.
                            if (replaySegmentCounters.size > 10_000) {
                                replaySegmentCounters.clear()
                            }
                            replaySegmentCounters
                                .computeIfAbsent(rid) { AtomicInteger(0) }
                                .getAndIncrement()
                        }

                    // Only create a synthetic replay event for the first segment of a session
                    if (lastReplayId == null && segmentId == 0) {
                        storeSyntheticReplayEvent(projectId, rid, segmentId, envelope)
                    }

                    storeReplayRecording(projectId, rid, segmentId, item.payload)
                    recordUsage(projectId, "replay", item)
                    lastReplayId = null
                    lastSegmentId = 0
                }

                "profile" -> {
                    logger.debug { "Profile payload: ${item.payload.take(500)}" }
                    if (storeProfile(projectId, item.payload)) {
                        recordUsage(projectId, "profile", item)
                    }
                }

                "feedback" -> {
                    logger.debug { "Feedback payload: ${item.payload.take(500)}" }
                    val feedback = json.decodeFromString<SentryFeedback>(item.payload)
                    if (storeFeedback(projectId, feedback)) {
                        recordUsage(projectId, "feedback", item)
                    }
                }

                else -> {
                    logger.debug { "Unknown item type: ${item.type}" }
                }
            }
        }
    }

    suspend fun processStoreEvent(
        projectId: Long,
        body: String
    ) {
        val event = json.decodeFromString<SentryEvent>(body)
        if (storeEvent(projectId, event)) {
            usageTracker.recordUsage(projectId, "error", body.toByteArray(StandardCharsets.UTF_8).size)
        }
    }

    private fun recordUsage(
        projectId: Long,
        eventType: String,
        item: EnvelopeItem
    ) {
        val byteSize = item.payloadBytes?.size ?: item.payload.toByteArray(StandardCharsets.UTF_8).size
        usageTracker.recordUsage(projectId, eventType, byteSize)
    }

    private suspend fun storeTransaction(
        projectId: Long,
        transaction: SentryTransaction
    ): Boolean {
        val rawEventId = transaction.event_id ?: UUID.randomUUID().toString()
        val eventId = normalizeUuid(rawEventId)
        val traceContext = transaction.contexts?.get("trace") as? JsonObject
        val traceId = traceContext?.get("trace_id")?.jsonPrimitive?.contentOrNull ?: ""
        val transactionOp = traceContext?.get("op")?.jsonPrimitive?.contentOrNull ?: ""
        val traceStatus = traceContext?.get("status")?.jsonPrimitive?.contentOrNull
        val transactionLevel = if (traceStatus == null || traceStatus == "ok") "info" else "error"

        val endTimestampMs = unixSecondsToMillis(transaction.timestamp ?: (System.currentTimeMillis() / 1000.0))
        val durationMs = durationMs(transaction.start_timestamp, transaction.timestamp)

        val contexts = transaction.contexts?.toString() ?: "{}"
        val breadcrumbs = transaction.breadcrumbs?.toString() ?: "[]"
        val request = transaction.request?.toString() ?: "{}"
        val message = transaction.transaction ?: transactionOp.ifBlank { "transaction" }

        val transactionData = TransactionEventInsertData(
            eventId = eventId,
            projectId = projectId,
            timestampMs = endTimestampMs,
            level = transactionLevel,
            message = message,
            platform = transaction.platform ?: "unknown",
            environment = transaction.environment ?: "production",
            release = transaction.release ?: "",
            dist = transaction.dist ?: "",
            serverName = transaction.server_name ?: "",
            userId = transaction.user?.id ?: "",
            userEmail = transaction.user?.email ?: "",
            userUsername = transaction.user?.username ?: "",
            userIpAddress = transaction.user?.ip_address ?: "",
            transactionName = transaction.transaction ?: "",
            transactionOp = transactionOp,
            durationMs = durationMs,
            tags = transaction.tags,
            contexts = contexts,
            breadcrumbs = breadcrumbs,
            request = request,
            sdkName = transaction.sdk?.name ?: "",
            sdkVersion = transaction.sdk?.version ?: ""
        )

        try {
            if (!eventRepository.insertTransaction(transactionData)) {
                return false
            }

            val spans = transaction.spans.orEmpty()

            try {
                insertSentrySpansToApm(
                    projectId = projectId,
                    eventId = eventId,
                    traceId = traceId,
                    transactionOp = transactionOp,
                    traceStatus = traceStatus,
                    transaction = transaction,
                    childSpans = spans
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to insert Sentry spans into apm_spans for transaction $eventId" }
            }

            logger.debug { "Transaction stored: $eventId for project $projectId (spans=${spans.size})" }

            // Detect ai.* spans and cross-insert into llm_generations
            val aiSpans = spans.filter { span -> (span.op ?: "").startsWith("ai.") }
            if (aiSpans.isNotEmpty()) {
                insertAiSpansAsLlmGenerations(projectId, traceId, transaction, aiSpans)
            }

            transaction.release?.takeIf { it.isNotBlank() }?.let { releaseVersion ->
                try {
                    releaseService.upsertReleaseFromEvent(projectId, releaseVersion, endTimestampMs)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to upsert release $releaseVersion for project $projectId" }
                }
            }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Error storing transaction in ClickHouse" }
            return false
        }
    }

    private suspend fun storeEvent(
        projectId: Long,
        event: SentryEvent
    ): Boolean {
        val eventId = event.event_id ?: UUID.randomUUID().toString()

        logger.debug {
            "Full event structure - exception: ${event.exception}, message: ${event.message}, platform: ${event.platform}"
        }

        // Convert Unix timestamp (seconds with fractional part) to milliseconds
        val timestamp = event.timestamp?.let { unixSecondsToMillis(it) } ?: System.currentTimeMillis()

        // Generate issue ID from fingerprint
        val fingerprint =
            if (event.fingerprint.isNullOrEmpty()) {
                generateFingerprint(event)
            } else {
                event.fingerprint
            }
        logger.debug { "Generated fingerprint: $fingerprint" }
        val issueId = generateIssueId(fingerprint)
        logger.debug { "Generated issue ID: $issueId" }

        // Extract exception info
        val firstException = event.exception?.values?.firstOrNull()
        val exceptionType = firstException?.type ?: ""
        val exceptionValue = firstException?.value ?: event.message ?: ""

        // Detect if this is a crash (unhandled exception)
        val mechanism = firstException?.mechanism
        val isHandled = mechanism?.get("handled")?.jsonPrimitive?.booleanOrNull ?: true
        val mechanismType = mechanism?.get("type")?.jsonPrimitive?.contentOrNull
        val isCrash = !isHandled || mechanismType == "onerror" || mechanismType == "onunhandledrejection"

        // Determine level: fatal for crashes, otherwise use provided level
        val eventLevel = if (isCrash && event.level == null) "fatal" else (event.level ?: "error")

        // Encode full exception with stack trace
        val stackTrace =
            event.exception?.let {
                Json.encodeToString(ExceptionInfo.serializer(), it)
            } ?: ""

        // Extract contexts
        val contexts = event.contexts?.toString() ?: "{}"
        val breadcrumbs = event.breadcrumbs?.toString() ?: "[]"
        val request = event.request?.toString() ?: "{}"

        // Build and insert error event via repository
        val eventData = ErrorEventInsertData(
            eventId = eventId,
            projectId = projectId,
            timestampMs = timestamp,
            level = eventLevel,
            message = exceptionValue,
            platform = event.platform ?: "unknown",
            environment = event.environment ?: "production",
            release = event.release ?: "",
            dist = event.dist ?: "",
            serverName = event.server_name ?: "",
            userId = event.user?.id ?: "",
            userEmail = event.user?.email ?: "",
            userUsername = event.user?.username ?: "",
            userIpAddress = event.user?.ip_address ?: "",
            exceptionType = exceptionType,
            exceptionValue = exceptionValue,
            stackTrace = stackTrace,
            fingerprint = fingerprint,
            issueId = issueId,
            tags = event.tags,
            contexts = contexts,
            breadcrumbs = breadcrumbs,
            request = request,
            sdkName = event.sdk?.name ?: "",
            sdkVersion = event.sdk?.version ?: ""
        )

        try {
            val success = eventRepository.insertErrorEvent(eventData)
            if (!success) return false
            logger.trace { "Event stored: $eventId for project $projectId" }
            CacheService.invalidatePattern("cache:issues:$projectId:*")
            event.release?.takeIf { it.isNotBlank() }?.let { releaseVersion ->
                try {
                    releaseService.upsertReleaseFromEvent(projectId, releaseVersion, timestamp)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to upsert release $releaseVersion for project $projectId" }
                }
            }

            // Check if this is a new issue and trigger notifications
            scope.launch {
                try {
                    if (isNewIssue(projectId, issueId)) {
                        logger.info { "New issue detected: $issueId for project $projectId" }
                        notificationService?.onNewIssue(projectId, issueId, event)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error checking for new issue notifications" }
                }
            }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Error storing event in ClickHouse" }
            return false
        }
    }

    private suspend fun storeFeedback(
        projectId: Long,
        feedback: SentryFeedback
    ): Boolean {
        // Validate project ID — allow negative demo project IDs (-1, -2, -3)
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for feedback, skipping insert" }
            return false
        }

        val feedbackId = feedback.event_id ?: UUID.randomUUID().toString()
        val timestamp =
            feedback.timestamp?.let {
                try {
                    java.time.Instant
                        .parse(it)
                        .toEpochMilli()
                } catch (e: Exception) {
                    logger.warn { "Failed to parse feedback timestamp: $it, using current time" }
                    System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()

        val feedbackContext = feedback.contexts?.get("feedback") as? JsonObject
        val message = feedbackContext?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        val contactEmail = feedbackContext?.get("contact_email")?.jsonPrimitive?.contentOrNull ?: ""
        val name = feedbackContext?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val url = feedbackContext?.get("url")?.jsonPrimitive?.contentOrNull ?: ""
        val associatedEventId = feedbackContext?.get("associated_event_id")?.jsonPrimitive?.contentOrNull ?: ""
        val replayId = feedbackContext?.get("replay_id")?.jsonPrimitive?.contentOrNull ?: ""

        val userId = feedback.user?.id ?: ""
        val userEmail = feedback.user?.email ?: contactEmail
        val userUsername = feedback.user?.username ?: ""
        val userIpAddress = feedback.user?.ip_address ?: ""

        val feedbackData = FeedbackInsertData(
            feedbackId = normalizeUuid(feedbackId),
            projectId = projectId,
            timestampMs = timestamp,
            message = message,
            contactEmail = contactEmail,
            name = name,
            url = url,
            associatedEventId = associatedEventId,
            replayId = replayId,
            environment = feedback.environment ?: "",
            release = feedback.release ?: "",
            platform = feedback.platform ?: "",
            userId = userId,
            userEmail = userEmail,
            userUsername = userUsername,
            userIpAddress = userIpAddress,
            sdkName = feedback.sdk?.name ?: "",
            sdkVersion = feedback.sdk?.version ?: "",
            tags = feedback.tags
        )

        try {
            val success = eventRepository.insertFeedback(feedbackData)
            if (!success) return false
            logger.info { "Feedback stored: $feedbackId for project $projectId" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Error storing feedback in ClickHouse" }
            return false
        }
    }

    private suspend fun storeReplayEvent(
        projectId: Long,
        replayEvent: SentryReplayEvent
    ): Boolean {
        // Validate project ID — allow negative demo project IDs (-1, -2, -3)
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for replay event, skipping insert" }
            return false
        }

        val replayId = replayEvent.replay_id ?: UUID.randomUUID().toString()
        val segmentId = replayEvent.segment_id ?: 0
        val ts = replayEvent.timestamp?.let { unixSecondsToMillis(it) } ?: System.currentTimeMillis()
        val startTs = replayEvent.replay_start_timestamp?.let { unixSecondsToMillis(it) } ?: ts

        val urls = replayEvent.urls?.take(100) ?: emptyList()
        val errorIds = replayEvent.error_ids ?: emptyList()
        val traceIds = replayEvent.trace_ids ?: emptyList()
        val tags = replayEvent.tags?.let { JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) }).toString() } ?: "{}"

        val contexts = replayEvent.contexts
        val browser = contexts?.get("browser") as? JsonObject
        val os = contexts?.get("os") as? JsonObject
        val device = contexts?.get("device") as? JsonObject
        val browserName = browser?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val browserVersion = browser?.get("version")?.jsonPrimitive?.contentOrNull ?: ""
        val osName = os?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val osVersion = os?.get("version")?.jsonPrimitive?.contentOrNull ?: ""
        val deviceName = device?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val deviceFamily = device?.get("family")?.jsonPrimitive?.contentOrNull ?: ""
        val activity =
            contexts
                ?.get("replay")
                ?.jsonObject
                ?.get("activity")
                ?.jsonPrimitive
                ?.intOrNull ?: 0

        val replayData = ReplayEventInsertData(
            replayId = normalizeUuid(replayId),
            projectId = projectId,
            segmentId = segmentId,
            timestampMs = ts,
            replayStartTimestampMs = startTs,
            urls = urls,
            errorIds = errorIds,
            traceIds = traceIds,
            environment = replayEvent.environment ?: "",
            release = replayEvent.release ?: "",
            platform = replayEvent.platform ?: "",
            userId = replayEvent.user?.id ?: "",
            userEmail = replayEvent.user?.email ?: "",
            userUsername = replayEvent.user?.username ?: "",
            userIpAddress = replayEvent.user?.ip_address ?: "",
            sdkName = replayEvent.sdk?.name ?: "",
            sdkVersion = replayEvent.sdk?.version ?: "",
            browserName = browserName,
            browserVersion = browserVersion,
            osName = osName,
            osVersion = osVersion,
            deviceName = deviceName,
            deviceFamily = deviceFamily,
            activity = activity,
            tags = tags
        )

        try {
            val success = eventRepository.insertReplayEvent(replayData)
            if (!success) return false
            logger.trace { "Replay event stored: $replayId segment $segmentId for project $projectId" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Error storing replay event in ClickHouse" }
            return false
        }
    }

    private suspend fun storeReplayRecording(
        projectId: Long,
        replayId: String,
        segmentId: Int,
        payload: String
    ) {
        try {
            eventRepository.insertReplayRecording(
                ReplayRecordingInsertData(
                    replayId = normalizeUuid(replayId),
                    projectId = projectId,
                    segmentId = segmentId,
                    timestampMs = System.currentTimeMillis(),
                    recordingData = payload
                )
            )
            logger.trace { "Replay recording stored: $replayId segment $segmentId for project $projectId" }
        } catch (e: Exception) {
            logger.error(e) { "Error storing replay recording in ClickHouse" }
        }
    }

    private suspend fun storeSyntheticReplayEvent(
        projectId: Long,
        replayId: String,
        segmentId: Int,
        @Suppress("UNUSED_PARAMETER") envelope: SentryEnvelope
    ) {
        // Validate project ID — allow negative demo project IDs (-1, -2, -3)
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for synthetic replay event, skipping insert" }
            return
        }

        val normalizedReplayId = normalizeUuid(replayId)
        val timestamp = System.currentTimeMillis()

        // Extract SDK info from envelope header if available
        val sdkName = "sentry.java.android"
        val sdkVersion = ""
        val platform = "android"

        try {
            eventRepository.insertReplayEvent(
                ReplayEventInsertData(
                    replayId = normalizedReplayId,
                    projectId = projectId,
                    segmentId = segmentId,
                    timestampMs = timestamp,
                    replayStartTimestampMs = timestamp,
                    urls = emptyList(),
                    errorIds = emptyList(),
                    traceIds = emptyList(),
                    environment = "e2e-testing",
                    release = "",
                    platform = platform,
                    userId = "",
                    userEmail = "",
                    userUsername = "",
                    userIpAddress = "",
                    sdkName = sdkName,
                    sdkVersion = sdkVersion,
                    browserName = "",
                    browserVersion = "",
                    osName = "",
                    osVersion = "",
                    deviceName = "",
                    deviceFamily = "",
                    activity = 0,
                    tags = "{}"
                )
            )
            logger.trace { "Synthetic replay event stored: $replayId for project $projectId" }
        } catch (e: Exception) {
            logger.error(e) { "Error storing synthetic replay event in ClickHouse" }
        }
    }

    private fun parseTransactionPayload(payload: String): SentryTransaction {
        return try {
            json.decodeFromString(payload)
        } catch (original: SerializationException) {
            val normalizedPayload =
                normalizeTimestampJsonPayload(
                    payload = payload,
                    timestampKeys = setOf("start_timestamp", "timestamp")
                ) ?: throw original
            logger.warn { "Retrying transaction decode after normalizing timestamp fields" }
            try {
                json.decodeFromString(normalizedPayload)
            } catch (_: SerializationException) {
                throw original
            }
        }
    }

    private fun parseReplayEventPayload(payload: String): SentryReplayEvent {
        return try {
            json.decodeFromString(payload)
        } catch (original: SerializationException) {
            val normalizedPayload =
                normalizeTimestampJsonPayload(
                    payload = payload,
                    timestampKeys = setOf("timestamp", "replay_start_timestamp")
                ) ?: throw original
            logger.warn { "Retrying replay_event decode after normalizing timestamp fields" }
            try {
                json.decodeFromString(normalizedPayload)
            } catch (_: SerializationException) {
                throw original
            }
        }
    }

    private fun normalizeTimestampJsonPayload(
        payload: String,
        timestampKeys: Set<String>
    ): String? {
        val parsed = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return null
        val normalized = normalizeTimestampElement(parsed, timestampKeys)
        if (normalized == parsed) return null
        return normalized.toString()
    }

    private fun normalizeTimestampElement(
        element: JsonElement,
        timestampKeys: Set<String>
    ): JsonElement {
        return when (element) {
            is JsonObject -> {
                var changed = false
                val normalizedEntries =
                    element.mapValues { (key, value) ->
                        val normalizedValue =
                            if (key in timestampKeys) {
                                normalizeTimestampValue(value)
                            } else {
                                normalizeTimestampElement(value, timestampKeys)
                            }
                        if (normalizedValue != value) changed = true
                        normalizedValue
                    }
                if (!changed) element else JsonObject(normalizedEntries)
            }

            is JsonArray -> {
                var changed = false
                val normalizedArray =
                    element.map { value ->
                        val normalizedValue = normalizeTimestampElement(value, timestampKeys)
                        if (normalizedValue != value) changed = true
                        normalizedValue
                    }
                if (!changed) element else JsonArray(normalizedArray)
            }

            else -> {
                element
            }
        }
    }

    private fun normalizeTimestampValue(value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive ?: return value
        if (!primitive.isString) return value

        val raw = primitive.contentOrNull ?: return value
        val parsed = parseTimestampString(raw) ?: return value
        return JsonPrimitive(parsed)
    }

    private fun parseTimestampString(raw: String): Double? {
        raw.toDoubleOrNull()?.let { return it }
        val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return null
        return instant.epochSecond.toDouble() + instant.nano / 1_000_000_000.0
    }

    private fun generateFingerprint(event: SentryEvent): List<String> {
        val firstException = event.exception?.values?.firstOrNull()
        val type = firstException?.type

        logger.trace { "=== FINGERPRINT GENERATION ===" }
        logger.trace { "Exception type: $type" }
        logger.trace { "Total frames: ${firstException?.stacktrace?.frames?.size}" }

        // Find the last in_app frame (innermost/actual error location), or fall back to the last frame
        val relevantFrame =
            firstException?.stacktrace?.frames?.findLast { it.in_app == true }
                ?: firstException?.stacktrace?.frames?.lastOrNull()

        val function = relevantFrame?.function
        val filename = relevantFrame?.filename

        logger.trace { "Selected frame: filename=$filename, function=$function, in_app=${relevantFrame?.in_app}" }

        val fingerprint =
            buildList {
                type?.let { add(it) }
                function?.let { add(it) }
                filename?.let { add(it) }
            }

        logger.trace { "Final fingerprint: $fingerprint" }

        return fingerprint.ifEmpty { listOf("{{ default }}") }
    }

    private fun generateIssueId(fingerprint: List<String>): String {
        val combined = fingerprint.joinToString("::")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun normalizeUuid(value: String): String {
        val trimmed = value.trim().lowercase()
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        if (uuidRegex.matches(trimmed)) return trimmed

        val hexRegex = Regex("^[0-9a-f]{32}$")
        if (hexRegex.matches(trimmed)) {
            return "${trimmed.substring(
                0,
                8
            )}-${trimmed.substring(
                8,
                12
            )}-${trimmed.substring(12, 16)}-${trimmed.substring(16, 20)}-${trimmed.substring(20)}"
        }

        return UUID.randomUUID().toString()
    }

    private suspend fun insertAiSpansAsLlmGenerations(
        projectId: Long,
        traceId: String,
        transaction: SentryTransaction,
        aiSpans: List<SentrySpan>
    ) {
        try {
            val generations = aiSpans.mapNotNull { span ->
                val spanStart = span.start_timestamp ?: return@mapNotNull null
                val spanEnd = span.timestamp ?: return@mapNotNull null
                val op = span.op ?: ""
                val data = span.data

                val model = data?.get("ai.model_id")?.jsonPrimitive?.contentOrNull
                    ?: data?.get("model")?.jsonPrimitive?.contentOrNull ?: ""
                val provider = data?.get("ai.provider")?.jsonPrimitive?.contentOrNull ?: ""
                val inputTokens = data?.get("ai.input_tokens")?.jsonPrimitive?.intOrNull
                    ?: data?.get("ai.prompt_tokens_used")?.jsonPrimitive?.intOrNull ?: 0
                val outputTokens = data?.get("ai.output_tokens")?.jsonPrimitive?.intOrNull
                    ?: data?.get("ai.completion_tokens_used")?.jsonPrimitive?.intOrNull ?: 0
                val totalTokens = data?.get("ai.total_tokens_used")?.jsonPrimitive?.intOrNull
                    ?: (inputTokens + outputTokens)
                val type = when {
                    op.contains("chat_completion") -> "chat"
                    op.contains("embedding") -> "embedding"
                    op.contains("tool_call") || op.contains("tool") -> "tool_call"
                    op.contains("agent") -> "agent"
                    op.contains("chain") || op.contains("pipeline") -> "chain"
                    op.contains("retriever") -> "retriever"
                    else -> "completion"
                }

                LlmGenerationInsertData(
                    generationId = UUID.randomUUID().toString(),
                    projectId = projectId,
                    traceId = traceId,
                    spanId = span.span_id ?: "",
                    parentSpanId = span.parent_span_id ?: "",
                    timestampMs = unixSecondsToMillis(spanEnd),
                    durationMs = durationMs(spanStart, spanEnd),
                    name = span.description ?: op,
                    model = model,
                    provider = provider,
                    type = type,
                    input = data?.get("ai.input_messages")?.toString() ?: "",
                    output = data?.get("ai.responses")?.toString() ?: "",
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = totalTokens,
                    status = if (span.status == "ok" || span.status == null) "success" else "error",
                    userId = transaction.user?.id ?: "",
                    environment = transaction.environment ?: "",
                    release = transaction.release ?: "",
                    tags = span.tags
                )
            }

            if (generations.isEmpty()) return

            val success = eventRepository.insertLlmGenerations(generations)
            if (!success) {
                logger.error { "Failed to insert ai.* spans as LLM generations" }
            } else {
                logger.info { "Cross-inserted ${generations.size} ai.* spans as LLM generations for project $projectId" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to cross-insert ai.* spans as LLM generations" }
        }
    }

    private fun unixSecondsToMillis(value: Double): Long {
        return (value * 1000).toLong()
    }

    private fun unixSecondsToMillis(value: Double?): Long? {
        return value?.let { unixSecondsToMillis(it) }
    }

    private fun durationMs(
        start: Double?,
        end: Double?
    ): Double {
        if (start == null || end == null) return 0.0
        return ((end - start) * 1000.0).coerceAtLeast(0.0)
    }

    private fun unixSecondsToNanos(value: Double): Long =
        (value * 1_000_000_000.0).toLong()

    private fun durationNanos(start: Double?, end: Double?): Long {
        if (start == null || end == null) return 0L
        return ((end - start) * 1_000_000_000.0).toLong().coerceAtLeast(0L)
    }

    private fun sentryStatusToError(status: String?): Int =
        if (status == null || status == "ok" || status == "cancelled") 0 else 1

    private suspend fun insertSentrySpansToApm(
        projectId: Long,
        eventId: String,
        traceId: String,
        transactionOp: String,
        traceStatus: String?,
        transaction: SentryTransaction,
        childSpans: List<SentrySpan>
    ) {
        if (traceId.isBlank()) {
            logger.debug { "No trace_id in transaction $eventId, skipping apm_spans insert" }
            return
        }

        val orgId = getOrganizationIdForProject(projectId)
        if (orgId == null) {
            logger.warn { "Missing organization for projectId $projectId, skipping apm_spans insert" }
            return
        }

        val clickhouseDb = ClickHouseClient.getDatabase()
        val service = transaction.server_name?.takeIf { it.isNotBlank() }
            ?: transaction.sdk?.name?.takeIf { it.isNotBlank() }
            ?: "sentry"
        val host = transaction.server_name ?: ""
        val env = transaction.environment ?: "production"
        val version = transaction.release ?: ""
        val transactionName = transaction.transaction ?: transactionOp.ifBlank { "transaction" }

        val baseMeta = mutableMapOf(
            "sentry.transaction_id" to eventId,
            "sentry.project_id" to projectId.toString()
        )
        transaction.tags?.let { baseMeta.putAll(it) }

        val rootMetrics = mutableMapOf<String, Double>()
        transaction.measurements?.let { measurements ->
            for ((key, value) in measurements) {
                try {
                    val valueObj = value.jsonObject
                    val numericValue = valueObj["value"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    if (numericValue != null) {
                        rootMetrics["measurement.$key"] = numericValue
                    }
                } catch (_: Exception) { }
            }
        }

        val rows = mutableListOf<String>()

        val rootSpanId = transaction.contexts?.get("trace")?.jsonObject
            ?.get("span_id")?.jsonPrimitive?.contentOrNull ?: ""
        if (rootSpanId.isNotBlank()) {
            val startTs = transaction.start_timestamp ?: (System.currentTimeMillis() / 1000.0)
            val (traceIdHigh, traceIdLow) = hexToULongPair(traceId)
            val (spanIdHigh, spanIdLow) = hexToULongPair(rootSpanId)

            rows.add(
                """(
                $spanIdLow, $spanIdHigh,
                $traceIdLow, $traceIdHigh,
                0, 0,
                $orgId,
                '${escapeSql(transactionName)}',
                '${escapeSql(service)}',
                '${escapeSql(transactionName)}',
                '${escapeSql(transactionOp)}',
                fromUnixTimestamp64Nano(${unixSecondsToNanos(startTs)}),
                ${durationNanos(transaction.start_timestamp, transaction.timestamp)},
                ${sentryStatusToError(traceStatus)},
                ${mapToSqlMap(baseMeta)},
                ${doubleMapToSqlMap(rootMetrics)},
                '${escapeSql(host)}',
                '${escapeSql(env)}',
                '${escapeSql(version)}',
                '$traceId',
                '$rootSpanId',
                '',
                'sentry'
            )"""
            )
        }

        for (span in childSpans) {
            val spanStart = span.start_timestamp ?: transaction.start_timestamp ?: continue
            val spanEnd = span.timestamp ?: transaction.timestamp ?: spanStart
            val spanId = span.span_id?.ifBlank { null } ?: UUID.randomUUID().toString().replace("-", "")
            val spanTraceId = span.trace_id ?: traceId

            val (traceIdHigh, traceIdLow) = hexToULongPair(spanTraceId)
            val (spanIdHigh, spanIdLow) = hexToULongPair(spanId)
            val parentHex = span.parent_span_id ?: ""
            val (parentIdHigh, parentIdLow) = hexToULongPair(parentHex)

            val spanMeta = baseMeta.toMutableMap()
            span.tags?.let { spanMeta.putAll(it) }
            span.data?.let { data ->
                for ((key, value) in data) {
                    try {
                        val str = value.jsonPrimitive.contentOrNull
                        if (str != null) spanMeta["data.$key"] = str
                    } catch (_: Exception) { }
                }
            }

            val spanMetrics = mutableMapOf<String, Double>()
            span.data?.let { data ->
                for ((key, value) in data) {
                    try {
                        val num = value.jsonPrimitive.contentOrNull?.toDoubleOrNull()
                        if (num != null) spanMetrics["data.$key"] = num
                    } catch (_: Exception) { }
                }
            }

            rows.add(
                """(
                $spanIdLow, $spanIdHigh,
                $traceIdLow, $traceIdHigh,
                $parentIdLow, $parentIdHigh,
                $orgId,
                '${escapeSql(transactionName)}',
                '${escapeSql(service)}',
                '${escapeSql(span.description ?: "")}',
                '${escapeSql(span.op ?: "")}',
                fromUnixTimestamp64Nano(${unixSecondsToNanos(spanStart)}),
                ${durationNanos(spanStart, spanEnd)},
                ${sentryStatusToError(span.status)},
                ${mapToSqlMap(spanMeta)},
                ${doubleMapToSqlMap(spanMetrics)},
                '${escapeSql(host)}',
                '${escapeSql(env)}',
                '${escapeSql(version)}',
                '${escapeSql(spanTraceId)}',
                '${escapeSql(spanId)}',
                '${escapeSql(parentHex)}',
                'sentry'
            )"""
            )
        }

        if (rows.isEmpty()) return

        val insert = """
            INSERT INTO `$clickhouseDb`.apm_spans (
                span_id, span_id_high,
                trace_id, trace_id_high,
                parent_id, parent_id_high,
                organization_id,
                name, service, resource, type,
                start, duration, error,
                meta, metrics, host, env, version,
                trace_id_hex, span_id_hex, parent_id_hex, source
            ) VALUES
            ${rows.joinToString(",\n")}
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            logger.error { "Failed to insert Sentry spans into apm_spans for transaction $eventId" }
        }

        val totalBytes = rows.sumOf { it.length }
        usageTracker.recordOrgUsage(orgId, "sentry_trace", totalBytes)
    }

    private suspend fun storeProfile(
        projectId: Long,
        payload: String
    ): Boolean {
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for profile, skipping insert" }
            return false
        }
        val orgId = getOrganizationIdForProject(projectId)
        if (orgId == null) {
            logger.error { "Missing organization for projectId $projectId, skipping profile insert" }
            return false
        }
        try {
            val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
            val payloadSize = payloadBytes.size
            if (payloadSize > maxProfilePayloadBytes) {
                logger.warn {
                    "Profile payload too large, skipping insert " +
                        "(projectId=$projectId, orgId=$orgId, bytes=$payloadSize, max=$maxProfilePayloadBytes)"
                }
                return false
            }
            val profileJson = json.parseToJsonElement(payload).jsonObject

            val transactionName = profileJson["transaction_name"]
                ?.jsonPrimitive?.contentOrNull ?: ""
            val platform = profileJson["platform"]
                ?.jsonPrimitive?.contentOrNull ?: ""
            val runtimeObj = profileJson["runtime"]?.jsonObject
            val runtimeName = runtimeObj?.get("name")
                ?.jsonPrimitive?.contentOrNull ?: ""
            val runtimeVersion = runtimeObj?.get("version")
                ?.jsonPrimitive?.contentOrNull ?: ""
            val runtime = if (runtimeVersion.isNotEmpty()) {
                "$runtimeName $runtimeVersion"
            } else {
                runtimeName
            }
            val environment = profileJson["environment"]
                ?.jsonPrimitive?.contentOrNull ?: ""
            val release = profileJson["release"]
                ?.jsonPrimitive?.contentOrNull ?: ""

            val profileId = profileJson["event_id"]
                ?.jsonPrimitive?.contentOrNull
                ?: UUID.randomUUID().toString()
            val normalizedId = normalizeUuid(profileId)

            val storageKey = "$orgId/$normalizedId.profile.json"
            val storageDir = java.io.File(profileStoragePath)
            val storageFile = java.io.File(storageDir, storageKey)
            storageFile.parentFile?.mkdirs()
            storageFile.writeBytes(payloadBytes)

            val nowMs = System.currentTimeMillis()
            val durationNs = profileJson["duration_ns"]
                ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: 0L

            val success = eventRepository.insertProfile(
                ProfileInsertData(
                    organizationId = orgId,
                    service = transactionName,
                    environment = environment,
                    release = release,
                    runtime = runtime,
                    platform = platform,
                    startTimeMs = nowMs,
                    endTimeMs = nowMs + durationNs / 1_000_000,
                    durationNs = durationNs,
                    storageKey = storageKey,
                    payloadSizeBytes = payloadSize
                )
            )
            if (!success) {
                logger.error { "Failed to insert Sentry profile into ClickHouse" }
                return false
            }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to store Sentry profile" }
            return false
        }
    }

    private suspend fun isNewIssue(
        projectId: Long,
        issueId: String
    ): Boolean {
        val cacheKey = "$projectId:$issueId"
        if (!knownIssueIds.add(cacheKey)) return false

        val count = try {
            eventRepository.getEventCountForIssue(projectId, issueId)
        } catch (e: Exception) {
            knownIssueIds.remove(cacheKey)
            throw e
        }

        return if (count > 1) {
            if (knownIssueIds.size > MAX_KNOWN_ISSUES) {
                knownIssueIds.clear()
            }
            false
        } else {
            knownIssueIds.remove(cacheKey)
            true
        }
    }
}
