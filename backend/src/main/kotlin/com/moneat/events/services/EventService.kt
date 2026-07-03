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

import com.moneat.apm.services.ApmServiceMapRollups
import com.moneat.apm.services.ApmServiceMapSpan
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.events.models.EnvelopeItem
import com.moneat.events.models.ExceptionInfo
import com.moneat.events.models.ExceptionValue
import com.moneat.events.models.SdkInfo
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.models.SentryEvent
import com.moneat.events.models.SentryFeedback
import com.moneat.events.models.SentryReplayEvent
import com.moneat.events.models.SentrySession
import com.moneat.events.models.SentrySessionAggregate
import com.moneat.events.models.SentrySessionAggregatesPayload
import com.moneat.events.models.SentrySpan
import com.moneat.events.models.SentryTransaction
import com.moneat.events.models.isFeedbackEventPayload
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.models.ErrorEventInsertData
import com.moneat.events.repositories.models.FeedbackInsertData
import com.moneat.events.repositories.models.LlmGenerationInsertData
import com.moneat.events.repositories.models.ProfileInsertData
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.ReplayRecordingInsertData
import com.moneat.events.repositories.models.SessionInsertData
import com.moneat.events.repositories.models.TransactionEventInsertData
import com.moneat.notifications.services.NotificationService
import com.moneat.otlp.hexToULongPair
import com.moneat.otlp.services.OtlpExceptionEvent
import com.moneat.shared.services.CacheService
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseSqlUtils.doubleMapToSqlMap
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.ClickHouseSqlUtils.mapToSqlMap
import com.moneat.utils.suspendRunCatching
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
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

        private const val LOG_PAYLOAD_PREVIEW_CHARS = 500
        private const val MAX_REPLAY_SEGMENT_COUNTERS = 10_000
        private const val MAX_REPLAY_URLS = 100
        private const val FINGERPRINT_HASH_LENGTH = 16
        private const val MS_PER_SECOND = 1000.0
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val UUID_SEG1 = 8
        private const val UUID_SEG2 = 12
        private const val UUID_SEG3 = 16
        private const val UUID_SEG4 = 20
        private val OTLP_STACK_FRAME_PATTERN = Regex("""[\w.$/<>-]+\([^)]*\)""")
        private const val SENTRY_SOURCE = "sentry"
        private const val SERVICE_TAG = "service"
        private const val SERVICE_NAME_TAG = "service.name"

        /** Keys set by the server for apm_spans; must not be overwritten by SDK tag maps. */
        private val SENTRY_APM_META_RESERVED_KEYS = setOf("sentry.transaction_id", "sentry.project_id")
        private val SESSION_ERROR_STATUSES = setOf("abnormal", "crashed", "errored")
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
    private val serviceNameCache = ConcurrentHashMap<Long, CachedEntry<String?>>()
    private val knownIssueIds = ConcurrentHashMap.newKeySet<String>()
    private val cacheTtlMs = 5 * 60 * 1000L // 5 minutes
    private val maxKnownIssues = 100_000

    // Track replay segment counters for mobile replays that lack a separate replay_event item.
    // Maps replay_id -> next segment counter. Cleaned up when map exceeds threshold.
    private val replaySegmentCounters = ConcurrentHashMap<String, AtomicInteger>()

    private data class LastReplayEventContext(
        val replayId: String?,
        val segmentId: Int,
        val timestampMs: Long?
    )

    private data class ResolvedReplayVideoContext(
        val replayId: String,
        val segmentId: Int,
        val embeddedReplayEvent: SentryReplayEvent?,
        val timestampMs: Long
    )

    suspend fun storeOtlpException(exception: OtlpExceptionEvent): Boolean {
        val projectId = exception.projectId ?: return false
        val eventId = UUID.randomUUID().toString()
        val fingerprint = generateOtlpFingerprint(exception)
        val issueId = generateIssueId(fingerprint)
        val tags = otlpExceptionTags(exception)
        val contexts = buildJsonObject {
            put("trace_id", JsonPrimitive(exception.traceIdHex))
            put("span_id", JsonPrimitive(exception.spanIdHex))
            put("service", JsonPrimitive(exception.service))
            put("service_namespace", JsonPrimitive(exception.serviceNamespace))
        }.toString()
        val organizationId = requireOrganizationIdForProject(projectId, "OTLP exception") ?: return false

        val eventData = ErrorEventInsertData(
            eventId = eventId,
            projectId = projectId,
            organizationId = organizationId,
            timestampMs = exception.timestampMs,
            level = "error",
            message = exception.exceptionMessage.ifBlank { exception.exceptionType },
            platform = "otel",
            environment = exception.environment.ifBlank { "production" },
            release = exception.serviceVersion,
            dist = "",
            serverName = exception.host,
            userId = "",
            userEmail = "",
            userUsername = "",
            userIpAddress = "",
            exceptionType = exception.exceptionType,
            exceptionValue = exception.exceptionMessage,
            stackTrace = exception.stackTrace,
            fingerprint = fingerprint,
            issueId = issueId,
            tags = tags,
            contexts = contexts,
            breadcrumbs = "[]",
            request = "{}",
            sdkName = "opentelemetry",
            sdkVersion = "",
        )

        val success = eventRepository.insertErrorEvent(eventData)
        if (!success) return false
        CacheService.invalidatePattern("cache:issues:$projectId:*")
        exception.serviceVersion.takeIf { it.isNotBlank() }?.let { releaseVersion ->
            suspendRunCatching {
                releaseService.upsertReleaseFromEvent(projectId, releaseVersion, exception.timestampMs)
            }.getOrElse { e ->
                logger.warn(e) { "Failed to upsert OTLP release $releaseVersion for project $projectId" }
            }
        }
        maybeNotifyOtlpIssue(projectId, issueId, eventId, exception, tags)
        return true
    }

    fun verifyProjectKey(
        projectId: Long,
        publicKey: String
    ): ProjectKeyVerification {
        val cacheKey = "$projectId:$publicKey"
        val now = System.currentTimeMillis()
        projectKeyCache[cacheKey]?.let { if (it.expiresAt > now) return it.value }

        val result = eventRepository.verifyProjectKey(projectId, publicKey)
        projectKeyCache[cacheKey] = CachedEntry(result, now + cacheTtlMs)
        return result
    }

    fun getOrganizationIdForProject(projectId: Long): Int? {
        val now = System.currentTimeMillis()
        orgIdCache[projectId]?.let { if (it.expiresAt > now) return it.value }

        val result = eventRepository.getOrganizationIdForProject(projectId)
        orgIdCache[projectId] = CachedEntry(result, now + cacheTtlMs)
        return result
    }

    private fun organizationIdForProject(projectId: Long): Int? =
        getOrganizationIdForProject(projectId)

    private fun requireOrganizationIdForProject(projectId: Long, signal: String): Int? {
        val organizationId = organizationIdForProject(projectId)
        if (organizationId == null) {
            logger.warn { "Missing organization for projectId $projectId, skipping $signal insert" }
        }
        return organizationId
    }

    private fun getServiceNameForProject(projectId: Long): String? {
        val now = System.currentTimeMillis()
        serviceNameCache[projectId]?.let { if (it.expiresAt > now) return it.value }

        val result = eventRepository.getServiceNameForProject(projectId)
        serviceNameCache[projectId] = CachedEntry(result, now + cacheTtlMs)
        return result
    }

    suspend fun processEnvelope(
        projectId: Long,
        envelope: SentryEnvelope
    ) {
        var lastReplayId: String? = null
        var lastSegmentId: Int = 0
        var lastReplayTimestampMs: Long? = null
        for (item in envelope.items) {
            logger.debug { "Processing envelope item type: ${item.type}" }
            when (item.type) {
                "event" -> handleEventItem(projectId, item)

                "transaction" -> {
                    logger.debug { "Transaction payload: ${item.payload.take(LOG_PAYLOAD_PREVIEW_CHARS)}" }
                    val transaction = parseTransactionPayload(item.payload)
                    if (storeTransaction(projectId, transaction)) {
                        recordUsage(projectId, "transaction", item)
                    }
                }

                "session" -> handleSessionItem(projectId, item)

                "sessions" -> handleSessionAggregatesItem(projectId, item)

                "replay_event" -> {
                    val replayEvent = parseReplayEventPayload(item.payload)
                    lastReplayId = replayEvent.replayId
                    lastSegmentId = replayEvent.segmentId ?: 0
                    lastReplayTimestampMs = replayEventTimestampMs(replayEvent)
                    if (storeReplayEvent(projectId, replayEvent)) {
                        recordUsage(projectId, "replay", item)
                    }
                }

                "replay_recording" -> {
                    val rid = lastReplayId ?: envelope.eventId
                    storeReplayRecording(
                        projectId,
                        rid,
                        lastSegmentId,
                        item.payload,
                        lastReplayTimestampMs ?: System.currentTimeMillis()
                    )
                    recordUsage(projectId, "replay", item)
                    lastReplayId = null
                    lastSegmentId = 0
                    lastReplayTimestampMs = null
                }

                "replay_video" -> {
                    val (newReplayId, newSegmentId) = handleReplayVideoItem(
                        projectId,
                        item,
                        envelope,
                        LastReplayEventContext(
                            replayId = lastReplayId,
                            segmentId = lastSegmentId,
                            timestampMs = lastReplayTimestampMs
                        ),
                    )
                    lastReplayId = newReplayId
                    lastSegmentId = newSegmentId
                    lastReplayTimestampMs = null
                }

                "profile" -> handleProfileItem(projectId, item)

                "feedback", "user_report" -> handleFeedbackItem(projectId, item)

                else -> {
                    logger.debug { "Unknown item type: ${item.type}" }
                }
            }
        }
    }

    private suspend fun handleEventItem(projectId: Long, item: EnvelopeItem) {
        logger.debug { "Event payload: ${item.payload.take(LOG_PAYLOAD_PREVIEW_CHARS)}" }
        if (item.isFeedbackEventPayload()) {
            handleFeedbackItem(projectId, item)
            return
        }

        val event = json.decodeFromString<SentryEvent>(item.payload)
        if (storeEvent(projectId, event)) {
            recordUsage(projectId, "error", item)
        }
    }

    private suspend fun handleSessionItem(projectId: Long, item: EnvelopeItem) {
        logger.debug { "Session payload: ${item.payload.take(LOG_PAYLOAD_PREVIEW_CHARS)}" }
        val session = json.decodeFromString<SentrySession>(item.payload)
        if (storeSession(projectId, session)) {
            recordUsage(projectId, "session", item)
        }
    }

    private suspend fun handleSessionAggregatesItem(projectId: Long, item: EnvelopeItem) {
        logger.debug { "Session aggregates payload: ${item.payload.take(LOG_PAYLOAD_PREVIEW_CHARS)}" }
        val payload = json.decodeFromString<SentrySessionAggregatesPayload>(item.payload)
        val rows = payload.aggregates.flatMap { aggregate -> aggregate.toSessionRows(projectId) }
        if (storeSessionRows(projectId, rows)) {
            recordUsage(projectId, "session", item)
        }
    }

    private suspend fun handleReplayVideoItem(
        projectId: Long,
        item: EnvelopeItem,
        envelope: SentryEnvelope,
        lastReplayEvent: LastReplayEventContext,
    ): Pair<String?, Int> {
        // Mobile replay uses replay_video instead of replay_recording.
        // The SDK may send a preceding replay_event item (with replay_id & segment_id)
        // or just a standalone replay_video. Handle both cases.
        val mobilePayloads = decodeMobileReplayPayloads(item)
        val resolved = resolveReplayVideoContext(envelope, lastReplayEvent, mobilePayloads)
        storeReplayVideoMetadata(projectId, lastReplayEvent, resolved)

        storeReplayRecording(projectId, resolved.replayId, resolved.segmentId, item.payload, resolved.timestampMs)
        recordUsage(projectId, "replay", item)
        return null to 0
    }

    private fun resolveReplayVideoContext(
        envelope: SentryEnvelope,
        lastReplayEvent: LastReplayEventContext,
        mobilePayloads: MobileReplaySegmentPayloads?
    ): ResolvedReplayVideoContext {
        val parsedEmbeddedReplayEvent =
            if (lastReplayEvent.replayId == null) parseEmbeddedReplayEvent(mobilePayloads) else null
        val replayId = lastReplayEvent.replayId ?: parsedEmbeddedReplayEvent?.replayId ?: envelope.eventId
        val segmentId = replayVideoSegmentId(lastReplayEvent, parsedEmbeddedReplayEvent, mobilePayloads, replayId)
        val embeddedReplayEvent =
            parsedEmbeddedReplayEvent?.copy(
                replayId = parsedEmbeddedReplayEvent.replayId ?: replayId,
                segmentId = parsedEmbeddedReplayEvent.segmentId ?: segmentId
            )
        val timestampMs =
            lastReplayEvent.timestampMs
                ?: embeddedReplayEvent?.let(::replayEventTimestampMs)
                ?: System.currentTimeMillis()

        return ResolvedReplayVideoContext(
            replayId = replayId,
            segmentId = segmentId,
            embeddedReplayEvent = embeddedReplayEvent,
            timestampMs = timestampMs
        )
    }

    private fun replayVideoSegmentId(
        lastReplayEvent: LastReplayEventContext,
        embeddedReplayEvent: SentryReplayEvent?,
        mobilePayloads: MobileReplaySegmentPayloads?,
        replayId: String
    ): Int =
        lastReplayEvent.replayId?.let { lastReplayEvent.segmentId }
            ?: embeddedReplayEvent?.segmentId
            ?: segmentIdFromMobileRecording(mobilePayloads)
            ?: nextFallbackReplaySegmentId(replayId)

    private suspend fun storeReplayVideoMetadata(
        projectId: Long,
        lastReplayEvent: LastReplayEventContext,
        resolved: ResolvedReplayVideoContext
    ) {
        if (lastReplayEvent.replayId != null) return
        resolved.embeddedReplayEvent?.let {
            storeReplayEvent(projectId, it)
            return
        }
        if (resolved.segmentId == 0) {
            storeSyntheticReplayEvent(projectId, resolved.replayId, resolved.segmentId, resolved.timestampMs)
        }
    }

    private fun decodeMobileReplayPayloads(item: EnvelopeItem): MobileReplaySegmentPayloads? {
        val rawBytes = MobileReplaySegmentPayloadParser.rawBytes(item.payload, item.payloadBytes)
        return MobileReplaySegmentPayloadParser.decode(rawBytes)
    }

    private fun parseEmbeddedReplayEvent(payloads: MobileReplaySegmentPayloads?): SentryReplayEvent? {
        val payload = payloads?.replayEvent ?: return null
        return runCatching {
            parseReplayEventPayload(String(payload, Charsets.UTF_8))
        }.onFailure { e ->
            logger.warn(e) { "Failed to parse embedded mobile replay_event payload" }
        }.getOrNull()
    }

    private fun segmentIdFromMobileRecording(payloads: MobileReplaySegmentPayloads?): Int? {
        val payload = payloads?.replayRecording ?: return null
        val recording = String(payload, Charsets.UTF_8)
        val arrayStart = recording.indexOf('[')
        val header = recording.substring(0, if (arrayStart == -1) recording.length else arrayStart).trim()
        if (header.isBlank()) return null
        return runCatching {
            json.parseToJsonElement(header).jsonObject["segment_id"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
    }

    private fun nextFallbackReplaySegmentId(replayId: String): Int {
        if (replaySegmentCounters.size > MAX_REPLAY_SEGMENT_COUNTERS) {
            replaySegmentCounters.clear()
        }
        return replaySegmentCounters.computeIfAbsent(replayId) { AtomicInteger(0) }.getAndIncrement()
    }

    private suspend fun handleProfileItem(projectId: Long, item: EnvelopeItem) {
        logger.debug { "Profile payload: ${item.payload.take(LOG_PAYLOAD_PREVIEW_CHARS)}" }
        if (storeProfile(projectId, item.payload)) {
            recordUsage(projectId, "profile", item)
        }
    }

    private suspend fun handleFeedbackItem(projectId: Long, item: EnvelopeItem) {
        logger.debug { "Feedback payload: ${item.payload.take(LOG_PAYLOAD_PREVIEW_CHARS)}" }
        val feedback = json.decodeFromString<SentryFeedback>(item.payload)
        if (storeFeedback(projectId, feedback, item.type)) {
            recordUsage(projectId, "feedback", item)
        }
    }

    suspend fun processStoreEvent(
        projectId: Long,
        body: String
    ) {
        if (EnvelopeItem("event", body).isFeedbackEventPayload()) {
            val feedback = json.decodeFromString<SentryFeedback>(body)
            val byteSize = body.toByteArray(StandardCharsets.UTF_8).size
            if (storeFeedback(projectId, feedback, "event")) {
                usageTracker.recordUsage(projectId, "feedback", byteSize)
            }
            return
        }

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

    private fun normalizeSentryServiceTags(
        projectId: Long,
        tags: Map<String, String>?
    ): Map<String, String> {
        val normalized = tags?.toMutableMap() ?: mutableMapOf()
        val existingServiceName = normalized[SERVICE_NAME_TAG]?.trim()?.takeIf { it.isNotBlank() }
        if (existingServiceName != null) return normalized

        val explicitService = normalized[SERVICE_TAG]?.trim()?.takeIf { it.isNotBlank() }
        normalized[SERVICE_NAME_TAG] = explicitService
            ?: getServiceNameForProject(projectId)?.trim()?.takeIf { it.isNotBlank() }
            ?: projectId.toString()
        return normalized
    }

    private suspend fun storeTransaction(
        projectId: Long,
        transaction: SentryTransaction
    ): Boolean {
        val rawEventId = transaction.eventId ?: UUID.randomUUID().toString()
        val eventId = normalizeUuid(rawEventId)
        val traceContext = transaction.contexts?.get("trace") as? JsonObject
        val traceId = traceContext?.get("trace_id")?.jsonPrimitive?.contentOrNull ?: ""
        val transactionOp = traceContext?.get("op")?.jsonPrimitive?.contentOrNull ?: ""
        val traceStatus = traceContext?.get("status")?.jsonPrimitive?.contentOrNull
        val transactionLevel = if (traceStatus == null || traceStatus == "ok") "info" else "error"

        val endTimestampMs = unixSecondsToMillis(transaction.timestamp ?: (System.currentTimeMillis() / MS_PER_SECOND))
        val durationMs = durationMs(transaction.startTimestamp, transaction.timestamp)

        val contexts = transaction.contexts?.toString() ?: "{}"
        val breadcrumbs = transaction.breadcrumbs?.toString() ?: "[]"
        val request = transaction.request?.toString() ?: "{}"
        val message = transaction.transaction ?: transactionOp.ifBlank { "transaction" }
        val organizationId = requireOrganizationIdForProject(projectId, "transaction") ?: return false
        val normalizedTags = normalizeSentryServiceTags(projectId, transaction.tags)

        val transactionData = TransactionEventInsertData(
            eventId = eventId,
            projectId = projectId,
            organizationId = organizationId,
            timestampMs = endTimestampMs,
            level = transactionLevel,
            message = message,
            platform = transaction.platform ?: "unknown",
            environment = transaction.environment ?: "production",
            release = transaction.release ?: "",
            dist = transaction.dist ?: "",
            serverName = transaction.serverName ?: "",
            userId = transaction.user?.id ?: "",
            userEmail = transaction.user?.email ?: "",
            userUsername = transaction.user?.username ?: "",
            userIpAddress = transaction.user?.ipAddress ?: "",
            transactionName = transaction.transaction ?: "",
            transactionOp = transactionOp,
            durationMs = durationMs,
            tags = normalizedTags,
            contexts = contexts,
            breadcrumbs = breadcrumbs,
            request = request,
            sdkName = transaction.sdk?.name ?: "",
            sdkVersion = transaction.sdk?.version ?: ""
        )

        suspendRunCatching {
            if (!eventRepository.insertTransaction(transactionData)) {
                return false
            }

            val spans = transaction.spans.orEmpty()

            suspendRunCatching {
                val apmInput = SentryApmInsertInput(
                    projectId = projectId,
                    eventId = eventId,
                    trace = SentryApmTraceInput(
                        traceId = traceId,
                        transactionOp = transactionOp,
                        traceStatus = traceStatus,
                    ),
                    transaction = transaction,
                    childSpans = spans,
                    normalizedTags = normalizedTags,
                )
                insertSentrySpansToApm(apmInput)
            }.getOrElse { e ->
                logger.warn(e) { "Failed to insert Sentry spans into apm_spans for transaction $eventId" }
            }

            logger.debug { "Transaction stored: $eventId for project $projectId (spans=${spans.size})" }

            // Detect ai.* spans and cross-insert into llm_generations
            val aiSpans = spans.filter { span -> (span.op ?: "").startsWith("ai.") }
            if (aiSpans.isNotEmpty()) {
                insertAiSpansAsLlmGenerations(projectId, organizationId, traceId, transaction, aiSpans)
            }

            transaction.release?.takeIf { it.isNotBlank() }?.let { releaseVersion ->
                suspendRunCatching {
                    releaseService.upsertReleaseFromEvent(projectId, releaseVersion, endTimestampMs)
                }.getOrElse { e ->
                    logger.warn(e) { "Failed to upsert release $releaseVersion for project $projectId" }
                }
            }
            return true
        }.getOrElse { e ->
            logger.error(e) { "Error storing transaction in ClickHouse" }
            return false
        }
    }

    private suspend fun storeEvent(
        projectId: Long,
        event: SentryEvent
    ): Boolean {
        val eventId = event.eventId ?: UUID.randomUUID().toString()

        logger.debug {
            "Full event structure - exception: ${event.exception}, message: ${event.message}, " +
                "platform: ${event.platform}"
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
        val organizationId = requireOrganizationIdForProject(projectId, "event") ?: return false
        val normalizedTags = normalizeSentryServiceTags(projectId, event.tags)

        // Build and insert error event via repository
        val eventData = ErrorEventInsertData(
            eventId = eventId,
            projectId = projectId,
            organizationId = organizationId,
            timestampMs = timestamp,
            level = eventLevel,
            message = exceptionValue,
            platform = event.platform ?: "unknown",
            environment = event.environment ?: "production",
            release = event.release ?: "",
            dist = event.dist ?: "",
            serverName = event.serverName ?: "",
            userId = event.user?.id ?: "",
            userEmail = event.user?.email ?: "",
            userUsername = event.user?.username ?: "",
            userIpAddress = event.user?.ipAddress ?: "",
            exceptionType = exceptionType,
            exceptionValue = exceptionValue,
            stackTrace = stackTrace,
            fingerprint = fingerprint,
            issueId = issueId,
            tags = normalizedTags,
            contexts = contexts,
            breadcrumbs = breadcrumbs,
            request = request,
            sdkName = event.sdk?.name ?: "",
            sdkVersion = event.sdk?.version ?: ""
        )

        suspendRunCatching {
            val success = eventRepository.insertErrorEvent(eventData)
            if (!success) return false
            logger.trace { "Event stored: $eventId for project $projectId" }
            CacheService.invalidatePattern("cache:issues:$projectId:*")
            event.release?.takeIf { it.isNotBlank() }?.let { releaseVersion ->
                suspendRunCatching {
                    releaseService.upsertReleaseFromEvent(projectId, releaseVersion, timestamp)
                }.getOrElse { e ->
                    logger.warn(e) { "Failed to upsert release $releaseVersion for project $projectId" }
                }
            }

            // Check if this is a new issue and trigger notifications
            scope.launch {
                suspendRunCatching {
                    if (isNewIssue(projectId, issueId)) {
                        logger.info { "New issue detected: $issueId for project $projectId" }
                        notificationService?.onNewIssue(projectId, issueId, event)
                    }
                }.getOrElse { e ->
                    logger.error(e) { "Error checking for new issue notifications" }
                }
            }
            return true
        }.getOrElse { e ->
            logger.error(e) { "Error storing event in ClickHouse" }
            return false
        }
    }

    private suspend fun storeFeedback(
        projectId: Long,
        feedback: SentryFeedback,
        itemType: String
    ): Boolean {
        // Validate project ID — allow negative demo project IDs (-1, -2, -3)
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for feedback, skipping insert" }
            return false
        }

        val feedbackId = if (itemType == "user_report") {
            UUID.randomUUID().toString()
        } else {
            feedback.eventId ?: UUID.randomUUID().toString()
        }
        val timestamp = feedback.timestamp?.let { unixSecondsToMillis(it) } ?: System.currentTimeMillis()

        val feedbackContext = feedback.contexts?.get("feedback") as? JsonObject
        val message = feedbackContext?.string("message") ?: feedback.message ?: feedback.comments ?: ""
        val contactEmail =
            feedbackContext?.string("contact_email")
                ?: feedbackContext?.string("email")
                ?: feedback.contactEmail
                ?: feedback.email
                ?: ""
        val name = feedbackContext?.string("name") ?: feedback.name ?: ""
        val url = feedbackContext?.string("url") ?: feedback.url ?: ""
        val associatedEventId =
            feedbackContext?.string("associated_event_id")
                ?: feedback.associatedEventId
                ?: if (itemType == "user_report") feedback.eventId.orEmpty() else ""
        val replayId = feedbackContext?.string("replay_id") ?: feedback.replayId ?: ""

        val userId = feedback.user?.id ?: ""
        val userEmail = feedback.user?.email ?: contactEmail
        val userUsername = feedback.user?.username ?: ""
        val userIpAddress = feedback.user?.ipAddress ?: ""
        val organizationId = requireOrganizationIdForProject(projectId, "feedback") ?: return false

        val feedbackData = FeedbackInsertData(
            feedbackId = normalizeUuid(feedbackId),
            projectId = projectId,
            organizationId = organizationId,
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
            tags = feedback.tags,
            sourceType = "sentry",
            sourceName = "Sentry-compatible SDK",
            sourceEventName = if (itemType == "event") "feedback" else itemType
        )

        suspendRunCatching {
            val success = eventRepository.insertFeedback(feedbackData)
            if (!success) return false
            logger.info { "Feedback stored: $feedbackId for project $projectId" }
            return true
        }.getOrElse { e ->
            logger.error(e) { "Error storing feedback in ClickHouse" }
            return false
        }
    }

    private suspend fun storeSession(
        projectId: Long,
        session: SentrySession
    ): Boolean {
        val row = session.toInsertData(projectId) ?: return false
        return storeSessionRows(projectId, listOf(row))
    }

    private suspend fun storeSessionRows(
        projectId: Long,
        rows: List<SessionInsertData>
    ): Boolean {
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for session, skipping insert" }
            return false
        }
        if (rows.isEmpty()) return false

        suspendRunCatching {
            val success = eventRepository.insertSessions(rows)
            if (!success) return false

            for (row in rows.distinctBy { it.release }) {
                suspendRunCatching {
                    releaseService.upsertReleaseFromEvent(projectId, row.release, row.startedMs)
                }.getOrElse { e ->
                    logger.warn(e) { "Failed to upsert release ${row.release} for project $projectId" }
                }
            }

            logger.debug { "Stored ${rows.size} session rows for project $projectId" }
            return true
        }.getOrElse { e ->
            logger.error(e) { "Error storing sessions in ClickHouse" }
            return false
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun SentrySession.toInsertData(projectId: Long): SessionInsertData? {
        val release = attrs?.release?.takeIf { it.isNotBlank() } ?: return null
        val organizationId = requireOrganizationIdForProject(projectId, "session") ?: return null
        val startedMs =
            started?.let { unixSecondsToMillis(it) }
                ?: timestamp?.let { unixSecondsToMillis(it) }
                ?: System.currentTimeMillis()
        val errors = sessionErrorCount(status, this.errors)

        return SessionInsertData(
            sessionId = normalizeUuid(sessionId ?: UUID.randomUUID().toString()),
            projectId = projectId,
            organizationId = organizationId,
            startedMs = startedMs,
            durationMs = durationToMillis(duration),
            status = normalizeSessionStatus(status, errors),
            errors = errors,
            release = release,
            environment = attrs.environment ?: "production",
            userId = distinctId ?: "",
            receivedAtMs = System.currentTimeMillis()
        )
    }

    private fun SentrySessionAggregate.toSessionRows(projectId: Long): List<SessionInsertData> {
        val release = attrs?.release?.takeIf { it.isNotBlank() } ?: return emptyList()
        val organizationId = requireOrganizationIdForProject(projectId, "session aggregate") ?: return emptyList()
        val defaults = SessionAggregateDefaults(
            projectId = projectId,
            organizationId = organizationId,
            startedMs = started?.let { unixSecondsToMillis(it) } ?: System.currentTimeMillis(),
            release = release,
            environment = attrs.environment ?: "production",
            userId = distinctId ?: "",
            receivedAtMs = System.currentTimeMillis()
        )

        return buildList {
            addAggregateSessionRows(defaults, status = "exited", count = exited, errors = 0)
            addAggregateSessionRows(defaults, status = "errored", count = errored, errors = 1)
            addAggregateSessionRows(defaults, status = "crashed", count = crashed, errors = 1)
            addAggregateSessionRows(defaults, status = "abnormal", count = abnormal, errors = 1)
            addAggregateSessionRows(defaults, status = "ok", count = ok, errors = 0)
        }
    }

    private fun MutableList<SessionInsertData>.addAggregateSessionRows(
        defaults: SessionAggregateDefaults,
        status: String,
        count: Int,
        errors: Int
    ) {
        repeat(count.coerceAtLeast(0)) {
            add(
                SessionInsertData(
                    sessionId = UUID.randomUUID().toString(),
                    projectId = defaults.projectId,
                    organizationId = defaults.organizationId,
                    startedMs = defaults.startedMs,
                    durationMs = 0.0,
                    status = status,
                    errors = errors,
                    release = defaults.release,
                    environment = defaults.environment,
                    userId = defaults.userId,
                    receivedAtMs = defaults.receivedAtMs
                )
            )
        }
    }

    private fun normalizeSessionStatus(status: String?, errors: Int): String {
        return when (status?.lowercase()) {
            "ok" -> "ok"
            "exited" -> "exited"
            "errored" -> "errored"
            "crashed" -> "crashed"
            "abnormal" -> "abnormal"
            else -> if (errors > 0) "abnormal" else "ok"
        }
    }

    private fun sessionErrorCount(status: String?, errors: Int?): Int {
        val explicitErrors = errors?.coerceAtLeast(0) ?: 0
        val normalizedStatus = status?.lowercase()
        val minimumErrors =
            if (normalizedStatus != null && normalizedStatus in SESSION_ERROR_STATUSES) 1 else 0
        return maxOf(explicitErrors, minimumErrors)
    }

    private fun durationToMillis(durationSeconds: Double?): Double =
        ((durationSeconds ?: 0.0) * MS_PER_SECOND).coerceAtLeast(0.0)

    private data class SessionAggregateDefaults(
        val projectId: Long,
        val organizationId: Int,
        val startedMs: Long,
        val release: String,
        val environment: String,
        val userId: String,
        val receivedAtMs: Long
    )

    private suspend fun storeReplayEvent(
        projectId: Long,
        replayEvent: SentryReplayEvent
    ): Boolean {
        // Validate project ID — allow negative demo project IDs (-1, -2, -3)
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for replay event, skipping insert" }
            return false
        }

        val replayId = replayEvent.replayId ?: UUID.randomUUID().toString()
        val segmentId = replayEvent.segmentId ?: 0
        val ts = replayEvent.timestamp?.let { unixSecondsToMillis(it) } ?: System.currentTimeMillis()
        val startTs = replayEvent.replayStartTimestamp?.let { unixSecondsToMillis(it) } ?: ts

        val urls = replayEvent.urls?.take(MAX_REPLAY_URLS) ?: emptyList()
        val errorIds = replayEvent.errorIds ?: emptyList()
        val traceIds = replayEvent.traceIds ?: emptyList()
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

        val organizationId = requireOrganizationIdForProject(projectId, "replay event") ?: return false

        val replayData = ReplayEventInsertData(
            replayId = normalizeUuid(replayId),
            projectId = projectId,
            organizationId = organizationId,
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
            userIpAddress = replayEvent.user?.ipAddress ?: "",
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

        suspendRunCatching {
            val success = eventRepository.insertReplayEvent(replayData)
            if (!success) return false
            logger.trace { "Replay event stored: $replayId segment $segmentId for project $projectId" }
            return true
        }.getOrElse { e ->
            logger.error(e) { "Error storing replay event in ClickHouse" }
            return false
        }
    }

    private fun replayEventTimestampMs(replayEvent: SentryReplayEvent): Long =
        replayEvent.timestamp
            ?.let { unixSecondsToMillis(it) }
            ?: replayEvent.replayStartTimestamp?.let { unixSecondsToMillis(it) }
            ?: System.currentTimeMillis()

    private suspend fun storeReplayRecording(
        projectId: Long,
        replayId: String,
        segmentId: Int,
        payload: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val organizationId = requireOrganizationIdForProject(projectId, "replay recording") ?: return
        suspendRunCatching {
            eventRepository.insertReplayRecording(
                ReplayRecordingInsertData(
                    replayId = normalizeUuid(replayId),
                    projectId = projectId,
                    organizationId = organizationId,
                    segmentId = segmentId,
                    timestampMs = timestampMs,
                    recordingData = payload
                )
            )
            logger.trace { "Replay recording stored: $replayId segment $segmentId for project $projectId" }
        }.getOrElse { e ->
            logger.error(e) { "Error storing replay recording in ClickHouse" }
        }
    }

    private suspend fun storeSyntheticReplayEvent(
        projectId: Long,
        replayId: String,
        segmentId: Int,
        timestamp: Long = System.currentTimeMillis()
    ) {
        // Validate project ID — allow negative demo project IDs (-1, -2, -3)
        if (projectId == 0L) {
            logger.error { "Invalid projectId $projectId for synthetic replay event, skipping insert" }
            return
        }

        val normalizedReplayId = normalizeUuid(replayId)
        val organizationId = requireOrganizationIdForProject(projectId, "synthetic replay event") ?: return

        suspendRunCatching {
            eventRepository.insertReplayEvent(
                ReplayEventInsertData(
                    replayId = normalizedReplayId,
                    projectId = projectId,
                    organizationId = organizationId,
                    segmentId = segmentId,
                    timestampMs = timestamp,
                    replayStartTimestampMs = timestamp,
                    urls = emptyList(),
                    errorIds = emptyList(),
                    traceIds = emptyList(),
                    environment = "",
                    release = "",
                    platform = "",
                    userId = "",
                    userEmail = "",
                    userUsername = "",
                    userIpAddress = "",
                    sdkName = "",
                    sdkVersion = "",
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
        }.getOrElse { e ->
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
        return instant.epochSecond.toDouble() + instant.nano / NS_PER_SECOND
    }

    private fun generateFingerprint(event: SentryEvent): List<String> {
        val firstException = event.exception?.values?.firstOrNull()
        val type = firstException?.type

        logger.trace { "=== FINGERPRINT GENERATION ===" }
        logger.trace { "Exception type: $type" }
        logger.trace { "Total frames: ${firstException?.stacktrace?.frames?.size}" }

        // Find the last in_app frame (innermost/actual error location), or fall back to the last frame
        val relevantFrame =
            firstException?.stacktrace?.frames?.findLast { it.inApp == true }
                ?: firstException?.stacktrace?.frames?.lastOrNull()

        val function = relevantFrame?.function
        val filename = relevantFrame?.filename

        logger.trace { "Selected frame: filename=$filename, function=$function, in_app=${relevantFrame?.inApp}" }

        val fingerprint =
            buildList {
                type?.let { add(it) }
                function?.let { add(it) }
                filename?.let { add(it) }
            }

        logger.trace { "Final fingerprint: $fingerprint" }

        return fingerprint.ifEmpty { listOf("{{ default }}") }
    }

    private fun generateOtlpFingerprint(exception: OtlpExceptionEvent): List<String> {
        val stackFrame = exception.stackTrace
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { isOtlpStackFrame(it) }

        return buildList {
            exception.exceptionType.takeIf { it.isNotBlank() }?.let { add(it) }
            stackFrame?.let { add(it) }
                ?: exception.exceptionMessage.takeIf { it.isNotBlank() }?.let { add(it) }
            exception.service.takeIf { it.isNotBlank() }?.let { add(it) }
        }.ifEmpty { listOf("{{ default }}") }
    }

    private fun isOtlpStackFrame(line: String): Boolean =
        line.isNotBlank() &&
            (line.startsWith("at ") || line.startsWith("File \"") || OTLP_STACK_FRAME_PATTERN.containsMatchIn(line))

    private fun otlpExceptionTags(exception: OtlpExceptionEvent): Map<String, String> =
        buildMap {
            put("source", "otlp_trace")
            put("trace_id", exception.traceIdHex)
            put("span_id", exception.spanIdHex)
            put("service", exception.service)
            if (exception.serviceNamespace.isNotBlank()) put("service.namespace", exception.serviceNamespace)
            if (exception.host.isNotBlank()) put("host", exception.host)
        }

    private suspend fun maybeNotifyOtlpIssue(
        projectId: Long,
        issueId: String,
        eventId: String,
        exception: OtlpExceptionEvent,
        tags: Map<String, String>,
    ) {
        scope.launch {
            suspendRunCatching {
                if (isNewIssue(projectId, issueId)) {
                    logger.info { "New OTLP trace issue detected: $issueId for project $projectId" }
                    notificationService?.onNewIssue(
                        projectId,
                        issueId,
                        SentryEvent(
                            eventId = eventId,
                            level = "error",
                            platform = "otel",
                            sdk = SdkInfo(name = "opentelemetry", version = ""),
                            exception = ExceptionInfo(
                                values = listOf(
                                    ExceptionValue(
                                        type = exception.exceptionType,
                                        value = exception.exceptionMessage,
                                    )
                                )
                            ),
                            message = exception.exceptionMessage.ifBlank { exception.exceptionType },
                            environment = exception.environment.ifBlank { "production" },
                            release = exception.serviceVersion.ifBlank { null },
                            tags = tags,
                            serverName = exception.host.ifBlank { null },
                        )
                    )
                }
            }.getOrElse { e ->
                logger.error(e) { "Failed to process new OTLP trace issue notification" }
            }
        }
    }

    private fun generateIssueId(fingerprint: List<String>): String {
        val combined = fingerprint.joinToString("::")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_HASH_LENGTH)
    }

    private fun normalizeUuid(value: String): String {
        val trimmed = value.trim().lowercase()
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        if (uuidRegex.matches(trimmed)) return trimmed

        val hexRegex = Regex("^[0-9a-f]{32}$")
        if (hexRegex.matches(trimmed)) {
            return "${trimmed.substring(0, UUID_SEG1)}-${trimmed.substring(UUID_SEG1, UUID_SEG2)}" +
                "-${trimmed.substring(UUID_SEG2, UUID_SEG3)}-${trimmed.substring(UUID_SEG3, UUID_SEG4)}" +
                "-${trimmed.substring(UUID_SEG4)}"
        }

        return UUID.randomUUID().toString()
    }

    private suspend fun insertAiSpansAsLlmGenerations(
        projectId: Long,
        organizationId: Int,
        traceId: String,
        transaction: SentryTransaction,
        aiSpans: List<SentrySpan>
    ) {
        suspendRunCatching {
            val generations = aiSpans.mapNotNull { span ->
                val spanStart = span.startTimestamp ?: return@mapNotNull null
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
                    organizationId = organizationId,
                    traceId = traceId,
                    spanId = span.spanId ?: "",
                    parentSpanId = span.parentSpanId ?: "",
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
                logger.info {
                    "Cross-inserted ${generations.size} ai.* spans as LLM generations for project $projectId"
                }
            }
        }.getOrElse { e ->
            logger.warn(e) { "Failed to cross-insert ai.* spans as LLM generations" }
        }
    }

    private fun unixSecondsToMillis(value: Double): Long {
        return (value * MS_PER_SECOND).toLong()
    }

    private fun unixSecondsToMillis(value: Double?): Long? {
        return value?.let { unixSecondsToMillis(it) }
    }

    private fun durationMs(
        start: Double?,
        end: Double?
    ): Double {
        if (start == null || end == null) return 0.0
        return ((end - start) * MS_PER_SECOND).coerceAtLeast(0.0)
    }

    private fun unixSecondsToNanos(value: Double): Long =
        (value * NS_PER_SECOND).toLong()

    private fun durationNanos(start: Double?, end: Double?): Long {
        if (start == null || end == null) return 0L
        return ((end - start) * NS_PER_SECOND).toLong().coerceAtLeast(0L)
    }

    private fun sentryStatusToError(status: String?): Int =
        if (status == null || status == "ok" || status == "cancelled") 0 else 1

    private class ApmSpanContext(
        val orgId: Int,
        val clickhouseDb: String,
        val service: String,
        val host: String,
        val env: String,
        val version: String,
        val transactionName: String,
        val baseMeta: Map<String, String>,
    )

    private class SentryApmTraceInput(
        val traceId: String,
        val transactionOp: String,
        val traceStatus: String?,
    )

    private class SentryApmInsertInput(
        val projectId: Long,
        val eventId: String,
        val trace: SentryApmTraceInput,
        val transaction: SentryTransaction,
        val childSpans: List<SentrySpan>,
        val normalizedTags: Map<String, String>,
    )

    private suspend fun insertSentrySpansToApm(
        input: SentryApmInsertInput
    ) {
        val projectId = input.projectId
        val eventId = input.eventId
        val traceId = input.trace.traceId
        val transactionOp = input.trace.transactionOp
        val traceStatus = input.trace.traceStatus
        val transaction = input.transaction
        val childSpans = input.childSpans
        val normalizedTags = input.normalizedTags

        if (traceId.isBlank()) {
            logger.debug { "No trace_id in transaction $eventId, skipping apm_spans insert" }
            return
        }

        val orgId = getOrganizationIdForProject(projectId)
        if (orgId == null) {
            logger.warn { "Missing organization for projectId $projectId, skipping apm_spans insert" }
            return
        }

        val baseMeta = mutableMapOf(
            "sentry.transaction_id" to eventId,
            "sentry.project_id" to projectId.toString()
        )
        mergeNonReservedTags(baseMeta, normalizedTags)

        val ctx = ApmSpanContext(
            orgId = orgId,
            clickhouseDb = ClickHouseClient.getDatabase(),
            service = normalizedTags.getValue(SERVICE_NAME_TAG),
            host = transaction.serverName ?: "",
            env = transaction.environment ?: "production",
            version = transaction.release ?: "",
            transactionName = transaction.transaction ?: transactionOp.ifBlank { "transaction" },
            baseMeta = baseMeta,
        )

        val rows = mutableListOf<String>()
        val serviceMapSpans = mutableListOf<ApmServiceMapSpan>()
        buildRootSpanRow(ctx, traceId, transactionOp, traceStatus, transaction)?.let {
            rows.add(it.sqlRow)
            serviceMapSpans.add(it.serviceMapSpan)
        }
        for (span in childSpans) {
            buildChildSpanRow(ctx, span, traceId, transaction)?.let {
                rows.add(it.sqlRow)
                serviceMapSpans.add(it.serviceMapSpan)
            }
        }

        if (rows.isNotEmpty()) {
            executeApmSpanInsert(ctx, rows, serviceMapSpans, eventId)
        }
    }

    private data class ApmSpanInsertRow(
        val sqlRow: String,
        val serviceMapSpan: ApmServiceMapSpan,
    )

    private fun buildRootSpanRow(
        ctx: ApmSpanContext,
        traceId: String,
        transactionOp: String,
        traceStatus: String?,
        transaction: SentryTransaction
    ): ApmSpanInsertRow? {
        val rootSpanId = transaction.contexts?.get("trace")?.jsonObject
            ?.get("span_id")?.jsonPrimitive?.contentOrNull ?: ""
        if (rootSpanId.isBlank()) return null

        val startTs = transaction.startTimestamp ?: (System.currentTimeMillis() / MS_PER_SECOND)
        val startNanos = unixSecondsToNanos(startTs)
        val duration = durationNanos(transaction.startTimestamp, transaction.timestamp)
        val error = sentryStatusToError(traceStatus)
        val (traceIdHigh, traceIdLow) = hexToULongPair(traceId)
        val (spanIdHigh, spanIdLow) = hexToULongPair(rootSpanId)
        val rootMetrics = extractMeasurementMetrics(transaction)

        val sqlRow = """
            (
            $spanIdLow, $spanIdHigh,
            $traceIdLow, $traceIdHigh,
            0, 0,
            ${ctx.orgId},
            '${escapeSql(ctx.transactionName)}',
            '${escapeSql(ctx.service)}',
            '${escapeSql(ctx.transactionName)}',
            '${escapeSql(transactionOp)}',
            fromUnixTimestamp64Nano($startNanos),
            $duration,
            $error,
            ${mapToSqlMap(ctx.baseMeta)},
            ${doubleMapToSqlMap(rootMetrics)},
            '${escapeSql(ctx.host)}',
            '${escapeSql(ctx.env)}',
            '${escapeSql(ctx.version)}',
            '$traceId',
            '$rootSpanId',
            '',
            '$SENTRY_SOURCE'
            )
        """.trimIndent()
        return ApmSpanInsertRow(
            sqlRow = sqlRow,
            serviceMapSpan = ApmServiceMapSpan(
                organizationId = ctx.orgId.toLong(),
                traceKey = traceId,
                spanKey = rootSpanId,
                parentKey = "",
                service = ctx.service,
                env = ctx.env,
                source = SENTRY_SOURCE,
                startNanos = startNanos,
                durationNanos = duration,
                error = error,
            ),
        )
    }

    private fun buildChildSpanRow(
        ctx: ApmSpanContext,
        span: SentrySpan,
        fallbackTraceId: String,
        transaction: SentryTransaction
    ): ApmSpanInsertRow? {
        val spanStart = span.startTimestamp ?: transaction.startTimestamp ?: return null
        val spanEnd = span.timestamp ?: transaction.timestamp ?: spanStart
        val spanId = span.spanId?.ifBlank { null } ?: UUID.randomUUID().toString().replace("-", "")
        val spanTraceId = span.traceId ?: fallbackTraceId
        val startNanos = unixSecondsToNanos(spanStart)
        val duration = durationNanos(spanStart, spanEnd)
        val error = sentryStatusToError(span.status)

        val (traceIdHigh, traceIdLow) = hexToULongPair(spanTraceId)
        val (spanIdHigh, spanIdLow) = hexToULongPair(spanId)
        val parentHex = span.parentSpanId ?: ""
        val (parentIdHigh, parentIdLow) = hexToULongPair(parentHex)

        val spanMeta = extractSpanMeta(span, ctx.baseMeta)
        val spanMetrics = extractSpanMetrics(span)

        val sqlRow = """
            (
            $spanIdLow, $spanIdHigh,
            $traceIdLow, $traceIdHigh,
            $parentIdLow, $parentIdHigh,
            ${ctx.orgId},
            '${escapeSql(ctx.transactionName)}',
            '${escapeSql(ctx.service)}',
            '${escapeSql(span.description ?: "")}',
            '${escapeSql(span.op ?: "")}',
            fromUnixTimestamp64Nano($startNanos),
            $duration,
            $error,
            ${mapToSqlMap(spanMeta)},
            ${doubleMapToSqlMap(spanMetrics)},
            '${escapeSql(ctx.host)}',
            '${escapeSql(ctx.env)}',
            '${escapeSql(ctx.version)}',
            '${escapeSql(spanTraceId)}',
            '${escapeSql(spanId)}',
            '${escapeSql(parentHex)}',
            '$SENTRY_SOURCE'
            )
        """.trimIndent()
        return ApmSpanInsertRow(
            sqlRow = sqlRow,
            serviceMapSpan = ApmServiceMapSpan(
                organizationId = ctx.orgId.toLong(),
                traceKey = spanTraceId,
                spanKey = spanId,
                parentKey = parentHex,
                service = ctx.service,
                env = ctx.env,
                source = SENTRY_SOURCE,
                startNanos = startNanos,
                durationNanos = duration,
                error = error,
            ),
        )
    }

    private fun extractMeasurementMetrics(transaction: SentryTransaction): Map<String, Double> {
        val metrics = mutableMapOf<String, Double>()
        transaction.measurements?.let { measurements ->
            for ((key, value) in measurements) {
                suspendRunCatching {
                    val numericValue = value.jsonObject["value"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    if (numericValue != null) metrics["measurement.$key"] = numericValue
                }.getOrElse { e ->
                    logger.debug(e) { "Skipping malformed measurement '$key'" }
                }
            }
        }
        return metrics
    }

    private fun mergeNonReservedTags(target: MutableMap<String, String>, tags: Map<String, String>) {
        for ((key, value) in tags) {
            if (key !in SENTRY_APM_META_RESERVED_KEYS) {
                target[key] = value
            }
        }
    }

    private fun extractSpanMeta(span: SentrySpan, baseMeta: Map<String, String>): Map<String, String> {
        val meta = baseMeta.toMutableMap()
        span.tags?.let { mergeNonReservedTags(meta, it) }
        span.data?.let { data ->
            for ((key, value) in data) {
                suspendRunCatching {
                    val str = value.jsonPrimitive.contentOrNull
                    if (str != null) meta["data.$key"] = str
                }.getOrElse { e ->
                    logger.debug(e) { "Skipping malformed span data key '$key'" }
                }
            }
        }
        return meta
    }

    private fun extractSpanMetrics(span: SentrySpan): Map<String, Double> {
        val metrics = mutableMapOf<String, Double>()
        span.data?.let { data ->
            for ((key, value) in data) {
                suspendRunCatching {
                    val num = value.jsonPrimitive.contentOrNull?.toDoubleOrNull()
                    if (num != null) metrics["data.$key"] = num
                }.getOrElse { e ->
                    logger.debug(e) { "Skipping non-numeric span data key '$key'" }
                }
            }
        }
        return metrics
    }

    private suspend fun executeApmSpanInsert(
        ctx: ApmSpanContext,
        rows: List<String>,
        serviceMapSpans: List<ApmServiceMapSpan>,
        eventId: String
    ) {
        val insert = """
            INSERT INTO `${ctx.clickhouseDb}`.apm_spans (
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
        if (response.status.isSuccess()) {
            ApmServiceMapRollups.insertForSpans(ctx.clickhouseDb, serviceMapSpans)
            usageTracker.recordOrgUsage(ctx.orgId, "sentry_trace", rows.size, rows.sumOf { it.length })
        } else {
            logger.error { "Failed to insert Sentry spans into apm_spans for transaction $eventId" }
        }
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
        suspendRunCatching {
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
        }.getOrElse { e ->
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

        val count = suspendRunCatching {
            eventRepository.getEventCountForIssue(projectId, issueId)
        }.getOrElse { e ->
            knownIssueIds.remove(cacheKey)
            throw e
        }

        return if (count > 1) {
            if (knownIssueIds.size > maxKnownIssues) {
                knownIssueIds.clear()
            }
            false
        } else {
            knownIssueIds.remove(cacheKey)
            true
        }
    }
}
