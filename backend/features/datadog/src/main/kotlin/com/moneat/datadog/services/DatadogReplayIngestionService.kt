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

package com.moneat.datadog.services

import com.moneat.datadog.models.DdReplaySegmentEvent
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.EventRepositoryImpl
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.ReplayRecordingInsertData
import com.moneat.ingest.DecompressionService
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val MAX_REPLAY_URLS = 100
private const val RRWEB_META_EVENT_TYPE = 4
private const val UUID_SEG1 = 8
private const val UUID_SEG2 = 12
private const val UUID_SEG3 = 16
private const val UUID_SEG4 = 20
private const val DATADOG_SDK_NAME = "@datadog/browser-rum"
private const val DATADOG_SOURCE_TYPE = "datadog"
private const val DATADOG_SOURCE_NAME = "Datadog RUM SDK"
private const val DEFAULT_REPLAY_PLATFORM = "browser"

private val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val hexUuidRegex = Regex("^[0-9a-f]{32}$")
private val logger = KotlinLogging.logger {}
private val replayJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

data class DatadogReplayIngestRequest(
    val organizationId: Int,
    val projectId: Long,
    val event: DdReplaySegmentEvent,
    val segmentBytes: ByteArray,
    val declaredEncoding: String?,
    val tags: Map<String, String> = emptyMap(),
)

data class DatadogReplayIngestResult(
    val replayId: String,
    val segmentId: Int,
    val recordCount: Int,
    val bytesStored: Int,
)

object DatadogReplayIngestionService {

    suspend fun ingestReplaySegment(
        request: DatadogReplayIngestRequest,
    ): DatadogReplayIngestResult = ingestReplaySegment(request, EventRepositoryImpl())

    internal suspend fun ingestReplaySegment(
        request: DatadogReplayIngestRequest,
        eventRepository: EventRepository,
    ): DatadogReplayIngestResult {
        require(request.projectId != 0L) { "Project-scoped Datadog API key required for replay ingestion" }
        val rawSessionId = request.event.session?.id?.trim().orEmpty()
        require(rawSessionId.isNotEmpty()) { "Datadog replay event missing session.id" }

        val decoded = decodeReplaySegment(request.segmentBytes, request.declaredEncoding)
        require(decoded.records.isNotEmpty()) { "Datadog replay segment contains no records" }

        val replayId = normalizeReplayId(rawSessionId)
        val segmentId = request.event.indexInView ?: 0
        val timestampMs = positiveTimestamp(request.event.start)
        val urls = extractUrls(decoded.records)
        val tagsJson = replayTagsJson(request.event, request.tags)
        val platform = request.event.source.ifBlank { DEFAULT_REPLAY_PLATFORM }
        val sdkVersion = request.tags["sdk_version"].orEmpty()

        val replayEvent = ReplayEventInsertData(
            replayId = replayId,
            projectId = request.projectId,
            organizationId = request.organizationId,
            segmentId = segmentId,
            timestampMs = timestampMs,
            replayStartTimestampMs = timestampMs,
            urls = urls,
            errorIds = emptyList(),
            traceIds = emptyList(),
            environment = request.tags["env"].orEmpty(),
            release = request.tags["version"].orEmpty(),
            platform = platform,
            userId = "",
            userEmail = "",
            userUsername = "",
            userIpAddress = "",
            sdkName = DATADOG_SDK_NAME,
            sdkVersion = sdkVersion,
            browserName = "",
            browserVersion = "",
            osName = "",
            osVersion = "",
            deviceName = "",
            deviceFamily = "",
            activity = request.event.recordsCount.coerceAtLeast(decoded.records.size),
            tags = tagsJson,
        )
        val recording = ReplayRecordingInsertData(
            replayId = replayId,
            projectId = request.projectId,
            organizationId = request.organizationId,
            segmentId = segmentId,
            timestampMs = timestampMs,
            recordingData = decoded.recordingData,
        )

        suspendRunCatching {
            val replayStored = eventRepository.insertReplayEvent(replayEvent)
            check(replayStored) { "Failed to insert replay event" }
            eventRepository.insertReplayRecording(recording)
        }.getOrElse { error ->
            logger.error(error) { "Failed to ingest Datadog replay segment" }
            throw error
        }

        return DatadogReplayIngestResult(
            replayId = replayId,
            segmentId = segmentId,
            recordCount = decoded.records.size,
            bytesStored = decoded.recordingData.toByteArray(StandardCharsets.UTF_8).size,
        )
    }

    internal fun decodeReplaySegment(
        data: ByteArray,
        declaredEncoding: String?,
    ): DecodedReplaySegment {
        val candidateBytes = buildDecodeCandidates(data, declaredEncoding)
        val parseErrors = mutableListOf<Throwable>()
        candidateBytes.forEach { candidate ->
            try {
                return parseReplaySegment(candidate.decodeToString())
            } catch (error: SerializationException) {
                parseErrors += error
            } catch (error: IllegalArgumentException) {
                parseErrors += error
            }
        }
        val firstError = parseErrors.firstOrNull()
        throw IllegalArgumentException("Invalid Datadog replay segment payload", firstError)
    }

    internal fun normalizeReplayId(rawId: String): String {
        val trimmed = rawId.trim().lowercase()
        require(trimmed.isNotEmpty()) { "Replay ID cannot be empty" }
        if (uuidRegex.matches(trimmed)) return trimmed
        if (hexUuidRegex.matches(trimmed)) {
            return "${trimmed.substring(0, UUID_SEG1)}-${trimmed.substring(UUID_SEG1, UUID_SEG2)}" +
                "-${trimmed.substring(UUID_SEG2, UUID_SEG3)}-${trimmed.substring(UUID_SEG3, UUID_SEG4)}" +
                "-${trimmed.substring(UUID_SEG4)}"
        }
        return UUID.nameUUIDFromBytes("datadog-replay:$trimmed".toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun buildDecodeCandidates(
        data: ByteArray,
        declaredEncoding: String?,
    ): List<ByteArray> {
        val candidates = mutableListOf<ByteArray>()
        val encoding = declaredEncoding?.trim()?.takeUnless { it.isBlank() || it.equals("identity", true) }
        if (encoding != null) {
            val decompressed = runCatching { DecompressionService.decompress(data, encoding) }
                .onFailure { logger.warn { "Failed to decompress Datadog replay segment as $encoding: ${it.message}" } }
                .getOrNull()
            if (decompressed != null) {
                candidates += decompressed
            }
        }
        candidates += data
        return candidates.distinctBy { it.contentHashCode() }
    }

    private fun parseReplaySegment(payload: String): DecodedReplaySegment {
        val element = replayJson.parseToJsonElement(payload)
        val records = when (element) {
            is JsonArray -> element
            is JsonObject -> element["records"] as? JsonArray
                ?: throw IllegalArgumentException("Datadog replay segment missing records array")
            else -> throw IllegalArgumentException("Datadog replay segment must be a JSON object or array")
        }
        return DecodedReplaySegment(
            records = records,
            recordingData = records.toString(),
        )
    }

    private fun positiveTimestamp(timestampMs: Long?): Long =
        timestampMs?.takeIf { it > 0 } ?: System.currentTimeMillis()

    private fun extractUrls(records: JsonArray): List<String> =
        records.asSequence()
            .mapNotNull { record ->
                val obj = record as? JsonObject ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.intOrNull
                if (type != RRWEB_META_EVENT_TYPE) return@mapNotNull null
                (obj["data"] as? JsonObject)
                    ?.get("href")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(MAX_REPLAY_URLS)
            .toList()

    private fun replayTagsJson(
        event: DdReplaySegmentEvent,
        tags: Map<String, String>,
    ): String {
        val merged = linkedMapOf(
            "source_type" to DATADOG_SOURCE_TYPE,
            "source_name" to DATADOG_SOURCE_NAME,
            "dd_source" to event.source,
            "dd_creation_reason" to event.creationReason,
            "dd_application_id" to event.application?.id.orEmpty(),
            "dd_session_id" to event.session?.id.orEmpty(),
            "dd_view_id" to event.view?.id.orEmpty(),
            "dd_records_count" to event.recordsCount.toString(),
            "dd_has_full_snapshot" to event.hasFullSnapshot?.toString().orEmpty(),
            "dd_raw_segment_size" to event.rawSegmentSize?.toString().orEmpty(),
            "dd_compressed_segment_size" to event.compressedSegmentSize?.toString().orEmpty(),
        )
        tags.forEach { (key, value) ->
            if (key.isNotBlank()) {
                merged[key] = value
            }
        }
        return JsonObject(
            merged
                .filterValues { it.isNotBlank() }
                .mapValues { (_, value) -> JsonPrimitive(value) }
        ).toString()
    }
}

internal data class DecodedReplaySegment(
    val records: JsonArray,
    val recordingData: String,
)
