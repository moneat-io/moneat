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
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.services.CacheService
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
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
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

class EventService(
    private val notificationService: NotificationService? = null,
    private val eventRepository: EventRepository
) {
    companion object {
        private const val DEFAULT_PROFILE_MAX_PAYLOAD_BYTES = 10 * 1024 * 1024 // 10 MiB
    }

    private val clickhouseDb: String get() = com.moneat.config.ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracker = UsageTrackingService.instance
    private val releaseService = ReleaseService()
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

        val endTimestampMs = unixSecondsToMillis(transaction.timestamp) ?: System.currentTimeMillis()
        val durationMs = durationMs(transaction.start_timestamp, transaction.timestamp)

        val contexts = transaction.contexts?.toString() ?: "{}"
        val breadcrumbs = transaction.breadcrumbs?.toString() ?: "[]"
        val request = transaction.request?.toString() ?: "{}"
        val message = transaction.transaction ?: transactionOp.ifBlank { "transaction" }

        val transactionInsert =
            """
            INSERT INTO `$clickhouseDb`.events (
                event_id, project_id, timestamp, event_type, level,
                message, platform, environment, release, dist, server_name,
                user_id, user_email, user_username, user_ip_address,
                exception_type, exception_value, stack_trace,
                transaction_name, transaction_op, duration_ms,
                fingerprint, issue_id, tags, contexts, breadcrumbs, request,
                sdk_name, sdk_version
            ) VALUES (
                toUUID('$eventId'),
                $projectId,
                fromUnixTimestamp64Milli($endTimestampMs),
                'transaction',
                '$transactionLevel',
                '${escapeSql(message)}',
                '${escapeSql(transaction.platform ?: "unknown")}',
                '${escapeSql(transaction.environment ?: "production")}',
                '${escapeSql(transaction.release ?: "")}',
                '${escapeSql(transaction.dist ?: "")}',
                '${escapeSql(transaction.server_name ?: "")}',
                '${escapeSql(transaction.user?.id ?: "")}',
                '${escapeSql(transaction.user?.email ?: "")}',
                '${escapeSql(transaction.user?.username ?: "")}',
                '${escapeSql(transaction.user?.ip_address ?: "")}',
                '',
                '',
                '',
                '${escapeSql(transaction.transaction ?: "")}',
                '${escapeSql(transactionOp)}',
                $durationMs,
                [],
                '',
                ${tagsToMap(transaction.tags)},
                '${escapeSql(contexts)}',
                '${escapeSql(breadcrumbs)}',
                '${escapeSql(request)}',
                '${escapeSql(transaction.sdk?.name ?: "")}',
                '${escapeSql(transaction.sdk?.version ?: "")}'
            )
            """.trimIndent()

        try {
            val transactionSuccess = eventRepository.executeClickHouseInsert(transactionInsert)
            if (!transactionSuccess) {
                return false
            }

            val spans = transaction.spans.orEmpty()
            if (spans.isNotEmpty()) {
                val spanRows =
                    spans.mapNotNull { span ->
                        val spanStart = span.start_timestamp ?: transaction.start_timestamp
                        val spanEnd = span.timestamp ?: transaction.timestamp ?: spanStart
                        if (spanStart == null || spanEnd == null) return@mapNotNull null

                        val spanStartMs = unixSecondsToMillis(spanStart)
                        val spanEndMs = unixSecondsToMillis(spanEnd)
                        val spanDurationMs = durationMs(spanStart, spanEnd)
                        val spanId = span.span_id?.ifBlank { null } ?: UUID.randomUUID().toString().replace("-", "")
                        val parentSpanId = span.parent_span_id ?: ""
                        val spanTraceId = span.trace_id ?: traceId
                        val spanData = span.data?.toString() ?: "{}"

                        """
                        (
                            '${escapeSql(spanId)}',
                            '${escapeSql(parentSpanId)}',
                            '${escapeSql(spanTraceId)}',
                            toUUID('$eventId'),
                            $projectId,
                            '${escapeSql(span.op ?: "")}',
                            '${escapeSql(span.description ?: "")}',
                            fromUnixTimestamp64Milli($spanStartMs),
                            fromUnixTimestamp64Milli($spanEndMs),
                            $spanDurationMs,
                            '${escapeSql(span.status ?: "")}',
                            ${tagsToMap(span.tags)},
                            '${escapeSql(spanData)}'
                        )
                        """.trimIndent()
                    }

                if (spanRows.isNotEmpty()) {
                    val spansInsert =
                        """
                        INSERT INTO `$clickhouseDb`.spans (
                            span_id, parent_span_id, trace_id, transaction_id, project_id,
                            op, description, start_timestamp, end_timestamp, duration_ms, status, tags, data
                        ) VALUES
                        ${spanRows.joinToString(",\n")}
                        """.trimIndent()

                    eventRepository.executeClickHouseInsertNoResult(spansInsert)
                }
            }

            logger.debug { "Transaction stored: $eventId for project $projectId (spans=${spans.size})" }

            // Detect ai.* spans and cross-insert into llm_generations
            val aiSpans =
                spans.filter { span ->
                    val op = span.op ?: ""
                    op.startsWith("ai.")
                }
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

        // Build ClickHouse insert query
        val query =
            """
            INSERT INTO `$clickhouseDb`.events (
                event_id, project_id, timestamp, event_type, level,
                message, platform, environment, release, dist, server_name,
                user_id, user_email, user_username, user_ip_address,
                exception_type, exception_value, stack_trace,
                fingerprint, issue_id, tags, contexts, breadcrumbs, request,
                sdk_name, sdk_version
            ) VALUES (
                toUUID('$eventId'),
                $projectId,
                fromUnixTimestamp64Milli($timestamp),
                'error',
                '$eventLevel',
                '${escapeSql(exceptionValue)}',
                '${escapeSql(event.platform ?: "unknown")}',
                '${escapeSql(event.environment ?: "production")}',
                '${escapeSql(event.release ?: "")}',
                '${escapeSql(event.dist ?: "")}',
                '${escapeSql(event.server_name ?: "")}',
                '${escapeSql(event.user?.id ?: "")}',
                '${escapeSql(event.user?.email ?: "")}',
                '${escapeSql(event.user?.username ?: "")}',
                '${escapeSql(event.user?.ip_address ?: "")}',
                '${escapeSql(exceptionType)}',
                '${escapeSql(exceptionValue)}',
                '${escapeSql(stackTrace)}',
                ${fingerprintToArray(fingerprint)},
                '$issueId',
                ${tagsToMap(event.tags)},
                '${escapeSql(contexts)}',
                '${escapeSql(breadcrumbs)}',
                '${escapeSql(request)}',
                '${escapeSql(event.sdk?.name ?: "")}',
                '${escapeSql(event.sdk?.version ?: "")}'
            )
            """.trimIndent()

        try {
            val success = eventRepository.executeClickHouseInsert(query)
            if (!success) return false
            logger.info { "Event stored: $eventId for project $projectId" }
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

        val insertQuery =
            """
            INSERT INTO `$clickhouseDb`.user_feedback (
                feedback_id, project_id, timestamp, message, contact_email, name, url,
                associated_event_id, replay_id, environment, release, platform,
                user_id, user_email, user_username, user_ip_address,
                sdk_name, sdk_version, tags, status
            ) VALUES (
                toUUID('${normalizeUuid(feedbackId)}'),
                $projectId,
                fromUnixTimestamp64Milli($timestamp),
                '${escapeSql(message)}',
                '${escapeSql(contactEmail)}',
                '${escapeSql(name)}',
                '${escapeSql(url)}',
                '${escapeSql(associatedEventId)}',
                '${escapeSql(replayId)}',
                '${escapeSql(feedback.environment ?: "")}',
                '${escapeSql(feedback.release ?: "")}',
                '${escapeSql(feedback.platform ?: "")}',
                '${escapeSql(userId)}',
                '${escapeSql(userEmail)}',
                '${escapeSql(userUsername)}',
                '${escapeSql(userIpAddress)}',
                '${escapeSql(feedback.sdk?.name ?: "")}',
                '${escapeSql(feedback.sdk?.version ?: "")}',
                ${tagsToMap(feedback.tags)},
                'unresolved'
            )
            """.trimIndent()

        try {
            val success = eventRepository.executeClickHouseInsert(insertQuery)
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

        val urlsArray = "[${urls.joinToString(",") { "'${escapeSql(it)}'" }}]"
        val errorIdsArray = "[${errorIds.joinToString(",") { "'${escapeSql(it)}'" }}]"
        val traceIdsArray = "[${traceIds.joinToString(",") { "'${escapeSql(it)}'" }}]"

        val replayEventInsert =
            """
            INSERT INTO `$clickhouseDb`.replay_events (
                replay_id, project_id, segment_id, timestamp, replay_start_timestamp,
                urls, error_ids, trace_ids, environment, release, platform,
                user_id, user_email, user_username, user_ip_address,
                sdk_name, sdk_version, browser_name, browser_version,
                os_name, os_version, device_name, device_family, activity, tags
            ) VALUES (
                toUUID('${normalizeUuid(replayId)}'),
                $projectId,
                $segmentId,
                fromUnixTimestamp64Milli($ts),
                fromUnixTimestamp64Milli($startTs),
                $urlsArray,
                $errorIdsArray,
                $traceIdsArray,
                '${escapeSql(replayEvent.environment ?: "")}',
                '${escapeSql(replayEvent.release ?: "")}',
                '${escapeSql(replayEvent.platform ?: "")}',
                '${escapeSql(replayEvent.user?.id ?: "")}',
                '${escapeSql(replayEvent.user?.email ?: "")}',
                '${escapeSql(replayEvent.user?.username ?: "")}',
                '${escapeSql(replayEvent.user?.ip_address ?: "")}',
                '${escapeSql(replayEvent.sdk?.name ?: "")}',
                '${escapeSql(replayEvent.sdk?.version ?: "")}',
                '${escapeSql(browserName)}',
                '${escapeSql(browserVersion)}',
                '${escapeSql(osName)}',
                '${escapeSql(osVersion)}',
                '${escapeSql(deviceName)}',
                '${escapeSql(deviceFamily)}',
                $activity,
                '${escapeSql(tags)}'
            )
            """.trimIndent()

        try {
            val success = eventRepository.executeClickHouseInsert(replayEventInsert)
            if (!success) return false
            logger.info { "Replay event stored: $replayId segment $segmentId for project $projectId" }
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
        val normalizedReplayId = normalizeUuid(replayId)
        val timestamp = System.currentTimeMillis()
        val escapedPayload = escapeSql(payload)

        val recordingInsert =
            """
            INSERT INTO `$clickhouseDb`.replay_segments (
                replay_id, project_id, segment_id, timestamp, recording_data
            ) VALUES (
                toUUID('$normalizedReplayId'),
                $projectId,
                $segmentId,
                fromUnixTimestamp64Milli($timestamp),
                '$escapedPayload'
            )
            """.trimIndent()

        try {
            eventRepository.executeClickHouseInsertNoResult(recordingInsert)
            logger.info { "Replay recording stored: $replayId segment $segmentId for project $projectId" }
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

        val replayEventInsert =
            """
            INSERT INTO `$clickhouseDb`.replay_events (
                replay_id, project_id, segment_id, timestamp, replay_start_timestamp,
                urls, error_ids, trace_ids, environment, release, platform,
                user_id, user_email, user_username, user_ip_address,
                sdk_name, sdk_version, browser_name, browser_version,
                os_name, os_version, device_name, device_family, activity, tags
            ) VALUES (
                toUUID('$normalizedReplayId'),
                $projectId,
                $segmentId,
                fromUnixTimestamp64Milli($timestamp),
                fromUnixTimestamp64Milli($timestamp),
                [],
                [],
                [],
                'e2e-testing',
                '',
                '$platform',
                '',
                '',
                '',
                '',
                '$sdkName',
                '$sdkVersion',
                '',
                '',
                '',
                '',
                '',
                '',
                0,
                '{}'
            )
            """.trimIndent()

        try {
            eventRepository.executeClickHouseInsertNoResult(replayEventInsert)
            logger.info { "Synthetic replay event stored: $replayId for project $projectId" }
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

        logger.info { "=== FINGERPRINT GENERATION ===" }
        logger.info { "Exception type: $type" }
        logger.info { "Total frames: ${firstException?.stacktrace?.frames?.size}" }

        // Find the last in_app frame (innermost/actual error location), or fall back to the last frame
        val relevantFrame =
            firstException?.stacktrace?.frames?.findLast { it.in_app == true }
                ?: firstException?.stacktrace?.frames?.lastOrNull()

        val function = relevantFrame?.function
        val filename = relevantFrame?.filename

        logger.info { "Selected frame: filename=$filename, function=$function, in_app=${relevantFrame?.in_app}" }

        val fingerprint =
            buildList {
                type?.let { add(it) }
                function?.let { add(it) }
                filename?.let { add(it) }
            }

        logger.info { "Final fingerprint: $fingerprint" }

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
            val rows =
                aiSpans.mapNotNull { span ->
                    val spanStart = span.start_timestamp ?: return@mapNotNull null
                    val spanEnd = span.timestamp ?: return@mapNotNull null
                    val spanDurationMs = durationMs(spanStart, spanEnd)
                    val spanTimestampMs = unixSecondsToMillis(spanEnd)
                    val generationId = UUID.randomUUID().toString()
                    val spanId = span.span_id ?: ""
                    val parentSpanId = span.parent_span_id ?: ""
                    val op = span.op ?: ""
                    val data = span.data

                    val model =
                        data?.get("ai.model_id")?.jsonPrimitive?.contentOrNull
                            ?: data?.get("model")?.jsonPrimitive?.contentOrNull ?: ""
                    val provider = data?.get("ai.provider")?.jsonPrimitive?.contentOrNull ?: ""
                    val inputTokens =
                        data?.get("ai.input_tokens")?.jsonPrimitive?.intOrNull
                            ?: data?.get("ai.prompt_tokens_used")?.jsonPrimitive?.intOrNull ?: 0
                    val outputTokens =
                        data?.get("ai.output_tokens")?.jsonPrimitive?.intOrNull
                            ?: data?.get("ai.completion_tokens_used")?.jsonPrimitive?.intOrNull ?: 0
                    val totalTokens =
                        data?.get("ai.total_tokens_used")?.jsonPrimitive?.intOrNull
                            ?: (inputTokens + outputTokens)
                    val input = data?.get("ai.input_messages")?.toString() ?: ""
                    val output = data?.get("ai.responses")?.toString() ?: ""

                    val type =
                        when {
                            op.contains("chat_completion") -> "chat"
                            op.contains("embedding") -> "embedding"
                            op.contains("tool_call") || op.contains("tool") -> "tool_call"
                            op.contains("agent") -> "agent"
                            op.contains("chain") || op.contains("pipeline") -> "chain"
                            op.contains("retriever") -> "retriever"
                            else -> "completion"
                        }

                    val status = if (span.status == "ok" || span.status == null) "success" else "error"

                    """(
                    toUUID('$generationId'),
                    $projectId,
                    '${escapeSql(traceId)}',
                    '${escapeSql(spanId)}',
                    '${escapeSql(parentSpanId)}',
                    fromUnixTimestamp64Milli($spanTimestampMs),
                    $spanDurationMs,
                    '${escapeSql(span.description ?: op)}',
                    '${escapeSql(model)}',
                    '${escapeSql(provider)}',
                    '$type',
                    '${escapeSql(input)}',
                    '${escapeSql(output)}',
                    $inputTokens,
                    $outputTokens,
                    $totalTokens,
                    0.0,
                    0, 0, 0,
                    '$status',
                    '', 0,
                    '${escapeSql(transaction.user?.id ?: "")}',
                    '',
                    '${escapeSql(transaction.environment ?: "")}',
                    '${escapeSql(transaction.release ?: "")}',
                    ${tagsToMap(span.tags)},
                    '{}'
                )
                    """.trimIndent()
                }

            if (rows.isEmpty()) return

            val query =
                """
                INSERT INTO `$clickhouseDb`.llm_generations (
                    generation_id, project_id, trace_id, span_id, parent_span_id,
                    timestamp, duration_ms, name, model, provider, type,
                    input, output, input_tokens, output_tokens, total_tokens, cost_usd,
                    temperature, max_tokens, top_p,
                    status, error_message, status_code,
                    user_id, session_id, environment, release, tags, metadata
                ) VALUES
                ${rows.joinToString(",\n")}
                """.trimIndent()

            val success = eventRepository.executeClickHouseInsert(query)
            if (!success) {
                logger.error { "Failed to insert ai.* spans as LLM generations" }
            } else {
                logger.info { "Cross-inserted ${rows.size} ai.* spans as LLM generations for project $projectId" }
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
            val insert = """
                INSERT INTO `$clickhouseDb`.profiles (
                    profile_id, organization_id,
                    host, service, env, version,
                    runtime, language, profile_type,
                    start_time, end_time, duration_ns,
                    storage_key, tags, size_bytes, source
                ) VALUES (
                    generateUUIDv4(),
                    $orgId,
                    '',
                    '${escapeSql(transactionName)}',
                    '${escapeSql(environment)}',
                    '${escapeSql(release)}',
                    '${escapeSql(runtime)}',
                    '${escapeSql(platform)}',
                    'cpu',
                    fromUnixTimestamp64Milli($nowMs),
                    fromUnixTimestamp64Milli(${nowMs + durationNs / 1_000_000}),
                    $durationNs,
                    '${escapeSql(storageKey)}',
                    map(),
                    $payloadSize,
                    'sentry'
                )
            """.trimIndent()

            val success = eventRepository.executeClickHouseInsert(insert)
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

    private fun fingerprintToArray(fingerprint: List<String>): String {
        return "[${fingerprint.joinToString(",") { "'${escapeSql(it)}'" }}]"
    }

    private fun tagsToMap(tags: Map<String, String>?): String {
        if (tags.isNullOrEmpty()) return "{}"
        return "{${tags.entries.joinToString(",") { "'${escapeSql(it.key)}':'${escapeSql(it.value)}'" }}}"
    }

    private suspend fun isNewIssue(
        projectId: Long,
        issueId: String
    ): Boolean {
        val cacheKey = "$projectId:$issueId"
        if (!knownIssueIds.add(cacheKey)) return false

        val count = eventRepository.getEventCountForIssue(projectId, issueId)

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
