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

import com.moneat.config.ClickHouseClient
import io.ktor.client.statement.bodyAsText
import com.moneat.events.models.ReplayDetailResponse
import com.moneat.events.models.ReplayListItem
import com.moneat.events.models.ReplayRecordingResponse
import com.moneat.events.models.ReplayTimelineItem
import com.moneat.events.models.ReplayTimelineResponse
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.value.ValueType
import java.time.Instant
import java.util.Base64

private val logger = KotlinLogging.logger {}

class ReplayService(
    private val queryHelper: DashboardQueryHelper
) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    private data class SegmentDecodeResult(
        val events: List<JsonElement>,
        val isMobileReplay: Boolean = false
    )

    suspend fun getProjectIdForReplay(replayId: String): Long? {
        val normalizedReplayId = queryHelper.normalizeUuid(replayId) ?: return null
        val query =
            """
            SELECT toInt64(project_id) as project_id
            FROM `$clickhouseDb`.replay_events
            WHERE toString(replay_id) = '$normalizedReplayId'
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()
        return queryHelper.executeProjectIdQuery(query, "Replay", replayId)
    }

    private suspend fun getProjectIdForIssue(issueId: String): Long? {
        val escapedIssueId = escapeSql(issueId)
        val query =
            """
            SELECT toInt64(project_id) as project_id
            FROM `$clickhouseDb`.issues FINAL
            WHERE issue_id = '$escapedIssueId'
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()
        return queryHelper.executeProjectIdQuery(query, "Issue", issueId)
    }

    private fun parseStringArray(element: JsonElement?): List<String> {
        val arr = element?.jsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    private fun buildReplayListItemFromJson(
        obj: JsonObject,
        projectId: Long,
        errorCount: Int
    ): ReplayListItem? {
        val replayId = obj["replay_id"]?.jsonPrimitive?.content ?: return null
        return ReplayListItem(
            replayId = replayId,
            projectId = obj["project_id"]?.jsonPrimitive?.long ?: projectId,
            startedAt = obj["started_at"]?.jsonPrimitive?.content ?: "",
            finishedAt = obj["finished_at"]?.jsonPrimitive?.content ?: "",
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            urls = parseStringArray(obj["urls"]),
            errorCount = errorCount,
            user = queryHelper.extractUserInfo(obj),
            browserName = obj["browser_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            browserVersion = obj["browser_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            osName = obj["os_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            osVersion = obj["os_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            activity = obj["activity"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    private fun isClickHouseError(statusCode: Int, body: String, context: String): Boolean {
        if (statusCode !in 200..299 || body.trimStart().startsWith("Code:")) {
            if (statusCode !in 200..299) {
                logger.error { "$context failed: $statusCode ${body.take(400)}" }
            } else {
                logger.error { "$context (ClickHouse): ${body.take(400)}" }
            }
            return true
        }
        return false
    }

    private fun mapErrorTimelineItem(obj: JsonObject, replayStartMs: Long): ReplayTimelineItem? {
        val tsMs = obj["ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val eventId = obj["event_id"]?.jsonPrimitive?.content ?: return null
        val exceptionType = obj["exception_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val message = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return ReplayTimelineItem(
            id = eventId,
            type = "error",
            timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
            offsetMs = (tsMs - replayStartMs).toDouble(),
            title = exceptionType ?: message ?: "Error",
            description = obj["exception_value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["message"]?.jsonPrimitive?.contentOrNull,
            durationMs = null,
            category = obj["level"]?.jsonPrimitive?.contentOrNull,
            eventId = eventId,
            issueId = obj["issue_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            traceId = null
        )
    }

    private fun mapTransactionTimelineItem(obj: JsonObject, replayStartMs: Long): ReplayTimelineItem? {
        val tsMs = obj["ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val eventId = obj["event_id"]?.jsonPrimitive?.content ?: return null
        val traceId = queryHelper.parseTraceContext(
            obj["contexts"]?.jsonPrimitive?.content ?: "{}"
        )?.get("trace_id")?.jsonPrimitive?.contentOrNull
        return ReplayTimelineItem(
            id = eventId,
            type = "transaction",
            timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
            offsetMs = (tsMs - replayStartMs).toDouble(),
            title = obj["transaction_name"]?.jsonPrimitive?.content ?: "Transaction",
            description = null,
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            category = obj["transaction_op"]?.jsonPrimitive?.contentOrNull,
            eventId = eventId,
            issueId = null,
            traceId = traceId
        )
    }

    private fun mapSpanTimelineItem(obj: JsonObject, replayStartMs: Long): ReplayTimelineItem? {
        val startTsMs = obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val spanId = obj["span_id"]?.jsonPrimitive?.content ?: return null
        val traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull
        return ReplayTimelineItem(
            id = "span-$traceId-$spanId",
            type = "span",
            timestamp = Instant.ofEpochMilli(startTsMs).toString(),
            offsetMs = (startTsMs - replayStartMs).toDouble(),
            title = obj["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: obj["op"]?.jsonPrimitive?.content ?: "Span",
            description = obj["op"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            category = obj["op"]?.jsonPrimitive?.contentOrNull,
            eventId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            issueId = null,
            traceId = traceId
        )
    }

    private suspend fun getReplayWindowErrorCount(
        projectId: Long,
        startMs: Long,
        endMs: Long,
        userId: String?,
        retentionDays: Int
    ): Int {
        if (endMs < startMs) return 0
        val userClause = userId?.takeIf { it.isNotBlank() }?.let { "AND user_id = '${escapeSql(it)}'" } ?: ""
        val query =
            """
            SELECT countDistinct(event_id) as count
            FROM `$clickhouseDb`.events
            WHERE project_id = $projectId
                AND event_type = 'error'
                AND timestamp >= fromUnixTimestamp64Milli($startMs)
                AND timestamp <= fromUnixTimestamp64Milli($endMs)
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                $userClause
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            if (response.status.value !in 200..299) return 0
            val line = response.bodyAsText().lines().firstOrNull { it.isNotBlank() } ?: return 0
            val obj = json.parseToJsonElement(line).jsonObject
            obj["count"]?.jsonPrimitive?.intOrNull ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to count replay window errors for project $projectId" }
            0
        }
    }

    private suspend fun getReplayWindowErrorIds(
        projectId: Long,
        startMs: Long,
        endMs: Long,
        userId: String?,
        retentionDays: Int,
        limit: Int = 200
    ): List<String> {
        if (endMs < startMs) return emptyList()
        val userClause = userId?.takeIf { it.isNotBlank() }?.let { "AND user_id = '${escapeSql(it)}'" } ?: ""
        val query =
            """
            SELECT toString(event_id) as event_id
            FROM `$clickhouseDb`.events
            WHERE project_id = $projectId
                AND event_type = 'error'
                AND timestamp >= fromUnixTimestamp64Milli($startMs)
                AND timestamp <= fromUnixTimestamp64Milli($endMs)
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                $userClause
            ORDER BY timestamp ASC
            LIMIT $limit
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            if (response.status.value !in 200..299) return emptyList()
            response
                .bodyAsText()
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        json
                            .parseToJsonElement(
                                line
                            ).jsonObject["event_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }.getOrNull()
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replay window error IDs for project $projectId" }
            emptyList()
        }
    }

    private fun parsedToJsonElementList(parsed: JsonElement): List<JsonElement> =
        when (parsed) {
            is JsonArray -> parsed.toList()
            else -> listOf(parsed)
        }

    private fun parseJsonEvents(
        payload: String,
        segmentIdx: Int
    ): List<JsonElement> {
        return try {
            parsedToJsonElementList(json.parseToJsonElement(payload))
        } catch (e: Exception) {
            logger.error(e) { "Segment $segmentIdx: Failed to parse replay payload as JSON" }
            emptyList()
        }
    }

    private fun readMsgpackBinaryOrString(unpacker: MessageUnpacker): ByteArray? {
        return when (unpacker.nextFormat.valueType) {
            ValueType.BINARY -> {
                val size = unpacker.unpackBinaryHeader()
                unpacker.readPayload(size)
            }

            ValueType.STRING -> {
                unpacker.unpackString().toByteArray(Charsets.UTF_8)
            }

            else -> {
                unpacker.skipValue()
                null
            }
        }
    }

    private fun extractSegmentIdFromJsonPayload(payload: String): Int? {
        return try {
            val obj = json.parseToJsonElement(payload).jsonObject
            obj["segment_id"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReplayRecordingBinary(
        payloadBytes: ByteArray,
        segmentIdx: Int
    ): Pair<Int?, List<JsonElement>> {
        val payload = String(payloadBytes, Charsets.UTF_8)
        val arrayStart = payload.indexOf('[')
        if (arrayStart == -1) {
            logger.warn { "Segment $segmentIdx: replay_recording payload does not contain event array" }
            return null to emptyList()
        }

        val header = payload.substring(0, arrayStart).trim()
        val segmentId = extractSegmentIdFromJsonPayload(header)
        val events = parseJsonEvents(payload.substring(arrayStart), segmentIdx)
        return segmentId to events
    }

    private fun annotateEventsWithSegmentId(
        events: List<JsonElement>,
        segmentId: Int
    ): List<JsonElement> {
        return events.map { event ->
            val obj = event as? JsonObject ?: return@map event
            if (obj["segment_id"] != null) {
                event
            } else {
                val updated = obj.toMutableMap()
                updated["segment_id"] = JsonPrimitive(segmentId)
                JsonObject(updated)
            }
        }
    }

    private fun isLikelyMp4(payloadBytes: ByteArray): Boolean {
        if (payloadBytes.size < 8) return false
        val boxType = String(payloadBytes.copyOfRange(4, 8), Charsets.US_ASCII)
        return boxType == "ftyp"
    }

    private fun decodeReplaySegment(
        recordingData: String,
        segmentIdx: Int
    ): SegmentDecodeResult {
        val rawBytes = runCatching { Base64.getDecoder().decode(recordingData) }.getOrNull()
            ?: return SegmentDecodeResult(events = parseJsonEvents(recordingData, segmentIdx))

        if (isJsonPayload(rawBytes)) {
            return SegmentDecodeResult(events = parseJsonEvents(String(rawBytes, Charsets.UTF_8), segmentIdx))
        }

        return decodeMsgpackReplaySegment(rawBytes, segmentIdx)
    }

    private fun isJsonPayload(rawBytes: ByteArray): Boolean {
        val firstNonWhitespace = rawBytes.firstOrNull {
            val code = it.toInt()
            code != ' '.code && code != '\n'.code && code != '\r'.code && code != '\t'.code
        }
        return firstNonWhitespace == '['.code.toByte() || firstNonWhitespace == '{'.code.toByte()
    }

    private fun decodeMsgpackReplaySegment(rawBytes: ByteArray, segmentIdx: Int): SegmentDecodeResult {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(rawBytes)
            val topMapSize = unpacker.unpackMapHeader()
            val events = mutableListOf<JsonElement>()
            val mobileSegmentIdHolder = mutableListOf<Int?>(null)

            repeat(topMapSize) {
                processMsgpackKey(unpacker, segmentIdx, events, mobileSegmentIdHolder)
            }

            unpacker.close()
            SegmentDecodeResult(events = events, isMobileReplay = true)
        } catch (e: Exception) {
            logger.error(e) { "Segment $segmentIdx: Failed to parse msgpack replay segment" }
            SegmentDecodeResult(events = emptyList(), isMobileReplay = true)
        }
    }

    private fun processMsgpackKey(
        unpacker: MessageUnpacker,
        segmentIdx: Int,
        events: MutableList<JsonElement>,
        mobileSegmentIdHolder: MutableList<Int?>
    ) {
        val key = unpacker.unpackString()
        val mobileSegmentId = mobileSegmentIdHolder.firstOrNull()
        when (key) {
            "replay_event" -> {
                val payload = readMsgpackBinaryOrString(unpacker)
                if (payload != null && mobileSegmentId == null) {
                    mobileSegmentIdHolder[0] = extractSegmentIdFromJsonPayload(String(payload, Charsets.UTF_8))
                }
            }

            "replay_recording" -> {
                val payload = readMsgpackBinaryOrString(unpacker) ?: return
                val (segmentIdFromRecording, recordingEvents) = parseReplayRecordingBinary(payload, segmentIdx)
                val effectiveSegmentId = segmentIdFromRecording ?: mobileSegmentId ?: segmentIdx
                if (mobileSegmentId == null) {
                    mobileSegmentIdHolder[0] = segmentIdFromRecording
                }
                events.addAll(annotateEventsWithSegmentId(recordingEvents, effectiveSegmentId))
            }

            "replay_video" -> {
                val payload = readMsgpackBinaryOrString(unpacker) ?: return
                events.add(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("mobile_replay_video"),
                            "segment_id" to JsonPrimitive(mobileSegmentId ?: segmentIdx),
                            "mime_type" to JsonPrimitive(
                                if (isLikelyMp4(payload)) "video/mp4" else "application/octet-stream"
                            ),
                            "size" to JsonPrimitive(payload.size),
                            "data" to JsonPrimitive(Base64.getEncoder().encodeToString(payload))
                        )
                    )
                )
            }

            else -> unpacker.skipValue()
        }
    }

    suspend fun getReplays(
        projectId: Long,
        page: Int = 1,
        limit: Int = 25,
        environment: String? = null,
        period: String = "7d",
        demoEpochMs: Long? = null
    ): List<ReplayListItem> {
        val offset = (page - 1) * limit
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val nowMs = demoEpochMs ?: System.currentTimeMillis()
        val periodMs =
            when (period) {
                "24h" -> 24 * 60 * 60 * 1000L
                "30d" -> 30 * 24 * 60 * 60 * 1000L
                "90d" -> 90 * 24 * 60 * 60 * 1000L
                else -> 7 * 24 * 60 * 60 * 1000L
            }
        val periodStartMs = nowMs - periodMs
        val retentionStartMs = nowMs - (retentionDays * 24 * 60 * 60 * 1000L)

        val envClause =
            if (environment != null && environment.isNotBlank()) {
                "AND environment = '${escapeSql(environment)}'"
            } else {
                ""
            }

        val query =
            """
            SELECT
                toString(replay_id) as replay_id,
                toInt64(project_id) as project_id,
                formatDateTime(min(replay_start_timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as started_at,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as finished_at,
                toUnixTimestamp64Milli(min(replay_start_timestamp)) as started_ms,
                toUnixTimestamp64Milli(max(timestamp)) as finished_ms,
                dateDiff('millisecond', min(replay_start_timestamp), max(timestamp)) as duration_ms,
                arrayFlatten(groupArray(urls)) as urls,
                length(arrayDistinct(arrayFlatten(groupArray(error_ids)))) as error_count,
                argMax(user_id, timestamp) as user_id,
                argMax(user_email, timestamp) as user_email,
                argMax(user_username, timestamp) as user_username,
                argMax(browser_name, timestamp) as browser_name,
                argMax(browser_version, timestamp) as browser_version,
                argMax(os_name, timestamp) as os_name,
                argMax(os_version, timestamp) as os_version,
                argMax(activity, timestamp) as activity
            FROM `$clickhouseDb`.replay_events
            WHERE $projectIdClause
                AND replay_start_timestamp >= fromUnixTimestamp64Milli($periodStartMs)
                AND timestamp >= fromUnixTimestamp64Milli($retentionStartMs)
                $envClause
            GROUP BY replay_id, project_id
            ORDER BY max(timestamp) DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val rows = queryHelper.executeJsonEachRowQuery(query, "Replays") ?: return emptyList()
            rows.mapNotNull { obj ->
                try {
                    val startedMs = obj["started_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val finishedMs = obj["finished_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val rawErrorCount = obj["error_count"]?.jsonPrimitive?.intOrNull ?: 0
                    val fallbackErrorCount =
                        if (rawErrorCount == 0 && startedMs != null && finishedMs != null) {
                            getReplayWindowErrorCount(
                                projectId,
                                startedMs,
                                finishedMs,
                                obj["user_id"]?.jsonPrimitive?.contentOrNull,
                                retentionDays
                            )
                        } else {
                            0
                        }
                    buildReplayListItemFromJson(obj, projectId, maxOf(rawErrorCount, fallbackErrorCount))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse replay list row" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replays for project $projectId" }
            emptyList()
        }
    }

    private suspend fun buildReplayDetailFromRow(
        obj: JsonObject,
        retentionDays: Int
    ): ReplayDetailResponse? {
        val replayId = obj["replay_id"]?.jsonPrimitive?.content ?: return null
        val objProjectId = obj["project_id"]?.jsonPrimitive?.long ?: return null
        val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
        val startedMs = obj["started_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val finishedMs = obj["finished_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val tagsStr = obj["tags"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val tagsMap =
            try {
                val tagsObj = json.parseToJsonElement(tagsStr) as? JsonObject ?: return null
                tagsObj.mapValues { it.value.jsonPrimitive.content }
            } catch (_: Exception) { emptyMap<String, String>() }
        val replayErrorIds = parseStringArray(obj["error_ids"]).distinct()
        val fallbackErrorIds =
            if (replayErrorIds.isEmpty() && startedMs != null && finishedMs != null) {
                getReplayWindowErrorIds(
                    projectId = objProjectId,
                    startMs = startedMs,
                    endMs = finishedMs,
                    userId = userId,
                    retentionDays = retentionDays
                )
            } else {
                emptyList()
            }
        val mergedErrorIds = (replayErrorIds + fallbackErrorIds).distinct()
        val fallbackErrorCount =
            if (replayErrorIds.isEmpty() && startedMs != null && finishedMs != null) {
                getReplayWindowErrorCount(
                    projectId = objProjectId,
                    startMs = startedMs,
                    endMs = finishedMs,
                    userId = userId,
                    retentionDays = retentionDays
                )
            } else {
                0
            }
        return ReplayDetailResponse(
            replayId = replayId,
            projectId = objProjectId,
            startedAt = obj["started_at"]?.jsonPrimitive?.content ?: "",
            finishedAt = obj["finished_at"]?.jsonPrimitive?.content ?: "",
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            urls = parseStringArray(obj["urls"]),
            errorCount = maxOf(mergedErrorIds.size, fallbackErrorCount),
            errorIds = mergedErrorIds,
            traceIds = parseStringArray(obj["trace_ids"]),
            segmentCount = obj["segment_count"]?.jsonPrimitive?.intOrNull ?: 0,
            environment = obj["environment"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            release = obj["release"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            platform = obj["platform"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            user = queryHelper.extractUserInfo(obj),
            browserName = obj["browser_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            browserVersion = obj["browser_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            osName = obj["os_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            osVersion = obj["os_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            activity = obj["activity"]?.jsonPrimitive?.intOrNull ?: 0,
            tags = tagsMap
        )
    }

    suspend fun getReplay(
        replayId: String,
        demoEpochMs: Long? = null
    ): ReplayDetailResponse? {
        val normalizedReplayId = queryHelper.normalizeUuid(replayId) ?: return null
        val projectId = getProjectIdForReplay(normalizedReplayId) ?: return null
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val query =
            """
            SELECT
                toString(replay_id) as replay_id,
                toInt64(project_id) as project_id,
                formatDateTime(min(replay_start_timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as started_at,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as finished_at,
                toUnixTimestamp64Milli(min(replay_start_timestamp)) as started_ms,
                toUnixTimestamp64Milli(max(timestamp)) as finished_ms,
                dateDiff('millisecond', min(replay_start_timestamp), max(timestamp)) as duration_ms,
                arrayFlatten(groupArray(urls)) as urls,
                arrayFlatten(groupArray(error_ids)) as error_ids,
                arrayFlatten(groupArray(trace_ids)) as trace_ids,
                count() as segment_count,
                argMax(environment, timestamp) as environment,
                argMax(release, timestamp) as release,
                argMax(platform, timestamp) as platform,
                argMax(user_id, timestamp) as user_id,
                argMax(user_email, timestamp) as user_email,
                argMax(user_username, timestamp) as user_username,
                argMax(browser_name, timestamp) as browser_name,
                argMax(browser_version, timestamp) as browser_version,
                argMax(os_name, timestamp) as os_name,
                argMax(os_version, timestamp) as os_version,
                argMax(activity, timestamp) as activity,
                argMax(tags, timestamp) as tags
            FROM `$clickhouseDb`.replay_events
            WHERE toString(replay_id) = '$normalizedReplayId'
                AND $projectIdClause
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY replay_id, project_id
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            val obj = json.parseToJsonElement(line).jsonObject
            buildReplayDetailFromRow(obj, retentionDays)
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replay $replayId" }
            null
        }
    }

    private suspend fun fetchAndAddTimelineItems(
        query: String,
        errorContext: String,
        failureMessage: String,
        replayStartMs: Long,
        mapper: (JsonObject, Long) -> ReplayTimelineItem?,
        items: MutableList<ReplayTimelineItem>,
        addedIds: MutableSet<String>
    ) {
        try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (isClickHouseError(response.status.value, body, errorContext)) return
            body
                .lines()
                .filter { it.isNotBlank() }
                .filter { !it.trimStart().startsWith("Code:") }
                .forEach { line ->
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    val item = mapper(obj, replayStartMs) ?: return@forEach
                    if (!addedIds.add(item.id)) return@forEach
                    items.add(item)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch $failureMessage" }
        }
    }

    private fun buildErrorsByIdQuery(
        projectId: Long,
        inClause: String,
        retentionDays: Int,
        demoEpochMs: Long?
    ): String =
        """
        SELECT
            toString(e.event_id) as event_id,
            formatDateTime(e.timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
            toUnixTimestamp64Milli(e.timestamp) as ts_ms,
            message,
            level,
            issue_id,
            exception_type,
            exception_value
        FROM `$clickhouseDb`.events e
        WHERE e.project_id = $projectId
            AND e.event_type = 'error'
            AND toString(e.event_id) IN ($inClause)
            AND ${queryHelper.timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
        FORMAT JSONEachRow
        """.trimIndent()

    private fun buildTransactionsByTraceQuery(
        projectId: Long,
        traceConditions: String,
        retentionDays: Int,
        demoEpochMs: Long?
    ): String =
        """
        SELECT
            toString(e.event_id) as event_id,
            formatDateTime(e.timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
            toUnixTimestamp64Milli(e.timestamp) as ts_ms,
            transaction_name,
            duration_ms,
            transaction_op,
            contexts
        FROM `$clickhouseDb`.events e
        WHERE e.project_id = $projectId
            AND e.event_type = 'transaction'
            AND ($traceConditions)
            AND ${queryHelper.timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
        FORMAT JSONEachRow
        """.trimIndent()

    private fun buildSpansByTraceQuery(
        projectId: Long,
        traceIdList: String,
        retentionDays: Int,
        demoEpochMs: Long?
    ): String =
        """
        SELECT
            span_id,
            trace_id,
            toString(transaction_id) as transaction_id,
            description,
            op,
            toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
            duration_ms
        FROM `$clickhouseDb`.spans
        WHERE project_id = $projectId
            AND trace_id IN ($traceIdList)
            AND ${queryHelper.timestampRetentionClause("start_timestamp", retentionDays, demoEpochMs)}
        FORMAT JSONEachRow
        """.trimIndent()

    suspend fun getReplayTimeline(
        replayId: String,
        demoEpochMs: Long? = null
    ): ReplayTimelineResponse {
        val replay = getReplay(replayId, demoEpochMs) ?: return ReplayTimelineResponse(emptyList(), 0L)
        val replayStartMs =
            try {
                Instant.parse(replay.startedAt).toEpochMilli()
            } catch (_: Exception) {
                return ReplayTimelineResponse(emptyList(), 0L)
            }
        val replayEndMs =
            try {
                Instant.parse(replay.finishedAt).toEpochMilli()
            } catch (_: Exception) {
                replayStartMs + 86400_000L
            }
        val projectId = replay.projectId
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val items = mutableListOf<ReplayTimelineItem>()
        val addedIds = mutableSetOf<String>()
        val userClause =
            replay.user
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?.let { "AND user_id = '${escapeSql(it)}'" } ?: ""

        val errorIdList = replay.errorIds.mapNotNull { queryHelper.normalizeUuid(it) }.distinct()
        if (errorIdList.isNotEmpty()) {
            val inClause = errorIdList.joinToString(",") { "'${escapeSql(it)}'" }
            val query = buildErrorsByIdQuery(projectId, inClause, retentionDays, demoEpochMs)
            fetchAndAddTimelineItems(
                query = query,
                errorContext = "Replay timeline errors by IDs",
                failureMessage = "replay timeline errors",
                replayStartMs = replayStartMs,
                mapper = ::mapErrorTimelineItem,
                items = items,
                addedIds = addedIds
            )
        }

        if (replay.traceIds.isNotEmpty()) {
            val traceIdList = replay.traceIds.distinct().map { "'${escapeSql(it)}'" }.joinToString(",")
            val traceConditions = "JSONExtractString(e.contexts, 'trace', 'trace_id') IN ($traceIdList)"
            val query = buildTransactionsByTraceQuery(projectId, traceConditions, retentionDays, demoEpochMs)
            fetchAndAddTimelineItems(
                query = query,
                errorContext = "Replay timeline transactions by trace IDs",
                failureMessage = "replay timeline transactions",
                replayStartMs = replayStartMs,
                mapper = ::mapTransactionTimelineItem,
                items = items,
                addedIds = addedIds
            )

            val spansQuery = buildSpansByTraceQuery(projectId, traceIdList, retentionDays, demoEpochMs)
            fetchAndAddTimelineItems(
                query = spansQuery,
                errorContext = "Replay timeline spans by trace IDs",
                failureMessage = "replay timeline spans",
                replayStartMs = replayStartMs,
                mapper = ::mapSpanTimelineItem,
                items = items,
                addedIds = addedIds
            )
        }

        // Link by time range: include errors/transactions/spans that occurred during the replay
        val errorsInRangeQuery =
            """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(ts_col, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
                toUnixTimestamp64Milli(ts_col) as ts_ms,
                message,
                level,
                issue_id,
                exception_type,
                exception_value
            FROM (SELECT *, timestamp as ts_col FROM `$clickhouseDb`.events WHERE project_id = $projectId AND event_type = 'error' $userClause)
            WHERE ts_col >= fromUnixTimestamp64Milli($replayStartMs)
                AND ts_col <= fromUnixTimestamp64Milli($replayEndMs)
                AND ${queryHelper.timestampRetentionClause("ts_col", retentionDays, demoEpochMs)}
            ORDER BY ts_col ASC
            LIMIT 100
            FORMAT JSONEachRow
            """.trimIndent()
        fetchAndAddTimelineItems(
            query = errorsInRangeQuery,
            errorContext = "Replay timeline errors by time range",
            failureMessage = "replay timeline errors by time range",
            replayStartMs = replayStartMs,
            mapper = ::mapErrorTimelineItem,
            items = items,
            addedIds = addedIds
        )

        val transactionsInRangeQuery =
            """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(ts_col, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
                toUnixTimestamp64Milli(ts_col) as ts_ms,
                transaction_name,
                duration_ms,
                transaction_op,
                contexts
            FROM (SELECT *, timestamp as ts_col FROM `$clickhouseDb`.events WHERE project_id = $projectId AND event_type = 'transaction' $userClause)
            WHERE ts_col >= fromUnixTimestamp64Milli($replayStartMs)
                AND ts_col <= fromUnixTimestamp64Milli($replayEndMs)
                AND ${queryHelper.timestampRetentionClause("ts_col", retentionDays, demoEpochMs)}
            ORDER BY ts_col ASC
            LIMIT 100
            FORMAT JSONEachRow
            """.trimIndent()
        fetchAndAddTimelineItems(
            query = transactionsInRangeQuery,
            errorContext = "Replay timeline transactions by time range",
            failureMessage = "replay timeline transactions by time range",
            replayStartMs = replayStartMs,
            mapper = ::mapTransactionTimelineItem,
            items = items,
            addedIds = addedIds
        )

        val spansInRangeQuery =
            """
            SELECT
                span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                description,
                op,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                duration_ms
            FROM `$clickhouseDb`.spans
            WHERE project_id = $projectId
                AND start_timestamp >= fromUnixTimestamp64Milli($replayStartMs)
                AND start_timestamp <= fromUnixTimestamp64Milli($replayEndMs)
                AND ${queryHelper.timestampRetentionClause("start_timestamp", retentionDays, demoEpochMs)}
            ORDER BY start_timestamp ASC
            LIMIT 200
            FORMAT JSONEachRow
            """.trimIndent()
        fetchAndAddTimelineItems(
            query = spansInRangeQuery,
            errorContext = "Replay timeline spans by time range",
            failureMessage = "replay timeline spans by time range",
            replayStartMs = replayStartMs,
            mapper = ::mapSpanTimelineItem,
            items = items,
            addedIds = addedIds
        )

        val sorted = items.sortedBy { it.offsetMs }
        return ReplayTimelineResponse(items = sorted, replayStartMs = replayStartMs)
    }

    suspend fun getReplayRecording(replayId: String): ReplayRecordingResponse? {
        val normalizedReplayId = queryHelper.normalizeUuid(replayId) ?: return null
        val projectId = getProjectIdForReplay(normalizedReplayId) ?: return null
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val query =
            """
            SELECT recording_data
            FROM `$clickhouseDb`.replay_segments
            WHERE toString(replay_id) = '$normalizedReplayId'
                AND $projectIdClause
                AND timestamp >= now64(3) - INTERVAL $retentionDays DAY
            ORDER BY segment_id ASC
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            val allEvents = mutableListOf<JsonElement>()
            var isMobileReplay = false

            val bodyLineCount = body.lines().count { it.isNotBlank() }
            logger.debug { "Processing replay recording response, body lines: $bodyLineCount" }

            body
                .lines()
                .filter { it.isNotBlank() }
                .forEachIndexed { segmentIdx, line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val recordingData = obj["recording_data"]?.jsonPrimitive?.content ?: return@forEachIndexed
                    val segment = decodeReplaySegment(recordingData, segmentIdx)
                    if (segment.isMobileReplay) {
                        isMobileReplay = true
                    }
                    allEvents.addAll(segment.events)
                }

            logger.info { "Msgpack decoding complete, extracted ${allEvents.size} total events from all segments" }

            // If mobile replay but no events decoded, return placeholder
            if (isMobileReplay && allEvents.isEmpty()) {
                logger.warn { "Mobile replay detected but no events extracted!" }
                ReplayRecordingResponse(
                    events =
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("mobile_replay_not_supported"),
                                "message" to JsonPrimitive(
                                    "Mobile session replays are not yet supported in the web viewer"
                                )
                            )
                        )
                    )
                )
            } else {
                logger.info { "Returning response with ${allEvents.size} events, isMobileReplay=$isMobileReplay" }
                ReplayRecordingResponse(events = allEvents)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replay recording $replayId" }
            null
        }
    }

    suspend fun getReplaysForIssue(
        issueId: String,
        limit: Int = 10
    ): List<ReplayListItem> {
        val projectId = getProjectIdForIssue(issueId) ?: return emptyList()
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val escapedIssueId = escapeSql(issueId)
        val retentionClause = queryHelper.timestampRetentionClause("e.timestamp", retentionDays)

        val query =
            """
            SELECT
                toString(r.replay_id) as replay_id,
                r.project_id,
                formatDateTime(min(r.replay_start_timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as started_at,
                formatDateTime(max(r.timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as finished_at,
                dateDiff('millisecond', min(r.replay_start_timestamp), max(r.timestamp)) as duration_ms,
                arrayFlatten(groupArray(r.urls)) as urls,
                length(arrayDistinct(arrayFlatten(groupArray(r.error_ids)))) as error_count,
                argMax(r.user_id, r.timestamp) as user_id,
                argMax(r.user_email, r.timestamp) as user_email,
                argMax(r.user_username, r.timestamp) as user_username,
                argMax(r.browser_name, r.timestamp) as browser_name,
                argMax(r.browser_version, r.timestamp) as browser_version,
                argMax(r.os_name, r.timestamp) as os_name,
                argMax(r.os_version, r.timestamp) as os_version,
                argMax(r.activity, r.timestamp) as activity
            FROM `$clickhouseDb`.replay_events r
            ARRAY JOIN arrayDistinct(r.error_ids) AS error_id
            INNER JOIN `$clickhouseDb`.events e
                ON toString(e.event_id) = error_id
                AND e.issue_id = '$escapedIssueId'
                AND e.project_id = $projectId
                AND e.event_type = 'error'
                AND $retentionClause
            WHERE r.project_id = $projectId
                AND r.timestamp >= now64(3) - INTERVAL $retentionDays DAY
            GROUP BY r.replay_id, r.project_id
            ORDER BY max(r.timestamp) DESC
            LIMIT $limit
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val rows = queryHelper.executeJsonEachRowQuery(query, "Replays for issue") ?: return emptyList()
            rows.mapNotNull { obj ->
                try {
                    buildReplayListItemFromJson(
                        obj,
                        projectId,
                        obj["error_count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse replay list row for issue" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replays for issue $issueId" }
            emptyList()
        }
    }
}
