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

import com.moneat.analytics.services.GeoIpService
import com.moneat.config.ClickHouseClient
import com.moneat.events.models.ReplayDetailResponse
import com.moneat.events.models.ReplayListItem
import com.moneat.events.models.ReplayRecordingResponse
import com.moneat.events.models.ReplayTimelineItem
import com.moneat.events.models.ReplayTimelineResponse
import com.moneat.shared.models.Projects
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_RANGE
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.value.ValueType
import java.time.Instant
import java.util.Base64

private val logger = KotlinLogging.logger {}

private data class ReplayListQuery(
    val scope: ServiceQueryScope,
    val retentionDays: Int,
    val page: Int,
    val limit: Int,
    val environment: String?,
    val period: String,
    val demoEpochMs: Long?
)

private data class ReplayWindowQuery(
    val projectId: Long,
    val startMs: Long,
    val endMs: Long,
    val userId: String?,
    val retentionDays: Int,
    val demoEpochMs: Long? = null
) {
    val isValid: Boolean get() = endMs >= startMs

    fun userClause(): String =
        userId?.takeIf { it.isNotBlank() }?.let { "AND user_id = '${escapeSql(it)}'" } ?: ""
}

private data class ReplayRetentionOptions(
    val retentionDays: Int,
    val demoEpochMs: Long? = null
)

class ReplayService(
    private val queryHelper: DashboardQueryHelper,
    private val projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
    private val geoIpService: GeoIpService = GeoIpService(),
) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    private data class ReplayContextEnrichment(
        val viewport: String? = null,
        val connection: String? = null,
        val hasRage: Boolean = false
    )

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

    private fun projectResourceId(projectId: Long): String =
        projectIdResolver.resourceIdFor(projectId) ?: projectId.toString()

    private fun firstEntryUrl(urls: List<String>): String? =
        urls.firstOrNull { it.isNotBlank() }

    private fun buildReplaySignals(
        errorCount: Int,
        activity: Int,
        hasRage: Boolean
    ): List<String> =
        buildList {
            if (errorCount > 0) add("error")
            if (activity == 0) add("dead_click")
            if (hasRage) add("rage_click")
        }

    private fun stringValue(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun intValue(obj: JsonObject, key: String): Int? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    private fun parseStoredJsonObject(raw: String?): JsonObject? =
        raw
            ?.takeIf { it.isNotBlank() && it != "{}" }
            ?.let { value ->
                suspendRunCatching { json.parseToJsonElement(value).jsonObject }.getOrNull()
            }

    private fun parseStoredJsonArray(raw: String?): JsonArray? =
        raw
            ?.takeIf { it.isNotBlank() && it != "[]" }
            ?.let { value ->
                suspendRunCatching { json.parseToJsonElement(value).jsonArray }.getOrNull()
            }

    private fun geoLabelForIp(ipAddress: String?): String? {
        val ip = ipAddress?.takeIf { it.isNotBlank() } ?: return null
        val geo = geoIpService.resolve(ip)
        if (geo.countryCode.isBlank()) return null
        val locality = geo.city.ifBlank { geo.subdivision }
        return if (locality.isBlank()) geo.countryCode else "$locality, ${geo.countryCode}"
    }

    private fun viewportFromContexts(contexts: JsonObject?): String? {
        val device = contexts?.get("device") as? JsonObject ?: return null
        val width = firstIntValue(device, "screen_width_pixels", "screen_width", "width")
        val height = firstIntValue(device, "screen_height_pixels", "screen_height", "height")
        return if (width != null && height != null) "$width x $height" else null
    }

    private fun firstIntValue(obj: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> intValue(obj, key) }

    private fun connectionFromBreadcrumbs(breadcrumbs: JsonArray?): String? {
        if (breadcrumbs == null) return null
        for (element in breadcrumbs) {
            val breadcrumb = element as? JsonObject ?: continue
            val data = breadcrumb["data"] as? JsonObject ?: continue
            val connection =
                firstStringValue(data, "network_type", "connection_type", "effective_type")
                    ?: firstStringValue(breadcrumb, "network_type", "connection_type", "effective_type")
            if (connection != null) return connection
        }
        return null
    }

    private fun firstStringValue(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> stringValue(obj, key) }

    private fun statusCodeFromJson(obj: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> intValue(obj, key) }

    private fun statusCodeFromText(text: String?): Int? {
        val value = text?.takeIf { it.isNotBlank() } ?: return null
        return HTTP_STATUS_REGEX.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isRageBreadcrumb(breadcrumb: JsonObject): Boolean {
        val haystack =
            listOfNotNull(
                stringValue(breadcrumb, "category"),
                stringValue(breadcrumb, "message"),
                stringValue(breadcrumb, "type"),
                (breadcrumb["data"] as? JsonObject)?.let { data ->
                    listOfNotNull(
                        stringValue(data, "action"),
                        stringValue(data, "message"),
                        stringValue(data, "reason")
                    ).joinToString(" ")
                }
            ).joinToString(" ").lowercase()
        return haystack.contains("rage") ||
            haystack.contains("frustrat") ||
            haystack.contains("dead click") ||
            haystack.contains("dead_click")
    }

    private fun buildReplayListItemFromJson(
        obj: JsonObject,
        projectId: Long,
        errorCount: Int,
        hasRage: Boolean = false
    ): ReplayListItem? {
        val replayId = obj["replay_id"]?.jsonPrimitive?.content ?: return null
        val objProjectId = obj["project_id"]?.jsonPrimitive?.long ?: projectId
        val urls = parseStringArray(obj["urls"])
        val activity = obj["activity"]?.jsonPrimitive?.intOrNull ?: 0
        return ReplayListItem(
            replayId = replayId,
            projectId = objProjectId,
            projectResourceId = projectResourceId(objProjectId),
            startedAt = obj["started_at"]?.jsonPrimitive?.content ?: "",
            finishedAt = obj["finished_at"]?.jsonPrimitive?.content ?: "",
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            urls = urls,
            errorCount = errorCount,
            user = queryHelper.extractUserInfo(obj),
            browserName = obj["browser_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            browserVersion = obj["browser_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            osName = obj["os_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            osVersion = obj["os_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            activity = activity,
            signals = buildReplaySignals(errorCount, activity, hasRage),
            entryUrl = firstEntryUrl(urls)
        )
    }

    private fun isClickHouseError(statusCode: Int, body: String, context: String): Boolean {
        if (statusCode !in HTTP_SUCCESS_RANGE || body.trimStart().startsWith("Code:")) {
            if (statusCode !in HTTP_SUCCESS_RANGE) {
                logger.error { "$context failed: $statusCode ${body.take(LOG_BODY_PREVIEW_LENGTH)}" }
            } else {
                logger.error { "$context (ClickHouse): ${body.take(LOG_BODY_PREVIEW_LENGTH)}" }
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
        val exceptionValue = obj["exception_value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return ReplayTimelineItem(
            id = eventId,
            type = "error",
            timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
            offsetMs = (tsMs - replayStartMs).toDouble(),
            title = exceptionType ?: message ?: "Error",
            description = exceptionValue ?: obj["message"]?.jsonPrimitive?.contentOrNull,
            durationMs = null,
            category = obj["level"]?.jsonPrimitive?.contentOrNull,
            eventId = eventId,
            issueId = obj["issue_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            traceId = null,
            statusCode = statusCodeFromText(listOfNotNull(message, exceptionValue).joinToString(" "))
        )
    }

    private fun mapTransactionTimelineItem(obj: JsonObject, replayStartMs: Long): ReplayTimelineItem? {
        val tsMs = obj["ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val eventId = obj["event_id"]?.jsonPrimitive?.content ?: return null
        val traceId = queryHelper.parseTraceContext(
            obj["contexts"]?.jsonPrimitive?.content ?: "{}"
        )?.get("trace_id")?.jsonPrimitive?.contentOrNull
        val title = obj["transaction_name"]?.jsonPrimitive?.content ?: "Transaction"
        return ReplayTimelineItem(
            id = eventId,
            type = "transaction",
            timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
            offsetMs = (tsMs - replayStartMs).toDouble(),
            title = title,
            description = null,
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            category = obj["transaction_op"]?.jsonPrimitive?.contentOrNull,
            eventId = eventId,
            issueId = null,
            traceId = traceId,
            statusCode = statusCodeFromJson(obj, "http_status_code", "status_code") ?: statusCodeFromText(title)
        )
    }

    private fun mapSpanTimelineItem(obj: JsonObject, replayStartMs: Long): ReplayTimelineItem? {
        val startTsMs = obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val spanId = obj["span_id"]?.jsonPrimitive?.content ?: return null
        val traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull
        val title = obj["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: obj["op"]?.jsonPrimitive?.content ?: "Span"
        return ReplayTimelineItem(
            id = "span-$traceId-$spanId",
            type = "span",
            timestamp = Instant.ofEpochMilli(startTsMs).toString(),
            offsetMs = (startTsMs - replayStartMs).toDouble(),
            title = title,
            description = obj["op"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            category = obj["op"]?.jsonPrimitive?.contentOrNull,
            eventId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            issueId = null,
            traceId = traceId,
            statusCode = statusCodeFromJson(obj, "http_status_code", "status_code") ?: statusCodeFromText(title)
        )
    }

    private suspend fun getReplayWindowErrorCount(window: ReplayWindowQuery): Int {
        if (!window.isValid) return 0
        val query =
            """
            SELECT countDistinct(event_id) as count
            FROM `$clickhouseDb`.events
            WHERE project_id = ${window.projectId}
                AND event_type = 'error'
                AND timestamp >= fromUnixTimestamp64Milli(${window.startMs})
                AND timestamp <= fromUnixTimestamp64Milli(${window.endMs})
                AND ${queryHelper.timestampRetentionClause("timestamp", window.retentionDays, window.demoEpochMs)}
                ${window.userClause()}
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = queryHelper.extractClickHouseBody(response) ?: return 0
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return 0
            val obj = json.parseToJsonElement(line).jsonObject
            obj["count"]?.jsonPrimitive?.intOrNull ?: 0
        }.getOrElse { e ->
            logger.error(e) { "Failed to count replay window errors for project ${window.projectId}" }
            0
        }
    }

    private suspend fun getReplayWindowErrorIds(
        window: ReplayWindowQuery,
        limit: Int = 200
    ): List<String> {
        if (!window.isValid) return emptyList()
        val query =
            """
            SELECT toString(event_id) as event_id
            FROM `$clickhouseDb`.events
            WHERE project_id = ${window.projectId}
                AND event_type = 'error'
                AND timestamp >= fromUnixTimestamp64Milli(${window.startMs})
                AND timestamp <= fromUnixTimestamp64Milli(${window.endMs})
                AND ${queryHelper.timestampRetentionClause("timestamp", window.retentionDays, window.demoEpochMs)}
                ${window.userClause()}
            ORDER BY timestamp ASC
            LIMIT $limit
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = queryHelper.extractClickHouseBody(response) ?: return emptyList()
            body
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        json
                            .parseToJsonElement(line)
                            .jsonObject["event_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }.getOrNull()
                }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch replay window error IDs for project ${window.projectId}" }
            emptyList()
        }
    }

    private suspend fun getReplayContextEnrichment(window: ReplayWindowQuery): ReplayContextEnrichment {
        if (!window.isValid) return ReplayContextEnrichment()
        val query =
            """
            SELECT contexts, breadcrumbs
            FROM `$clickhouseDb`.events
            WHERE project_id = ${window.projectId}
                AND timestamp >= fromUnixTimestamp64Milli(${window.startMs})
                AND timestamp <= fromUnixTimestamp64Milli(${window.endMs})
                AND ${queryHelper.timestampRetentionClause("timestamp", window.retentionDays, window.demoEpochMs)}
                ${window.userClause()}
            ORDER BY timestamp ASC
            LIMIT $REPLAY_CONTEXT_EVENT_LIMIT
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val rows = queryHelper.executeJsonEachRowQuery(query, "Replay context enrichment")
                ?: return ReplayContextEnrichment()
            var viewport: String? = null
            var connection: String? = null
            var hasRage = false

            for (row in rows) {
                val contexts = parseStoredJsonObject(stringValue(row, "contexts"))
                val breadcrumbs = parseStoredJsonArray(stringValue(row, "breadcrumbs"))
                viewport = viewport ?: viewportFromContexts(contexts)
                connection = connection ?: connectionFromBreadcrumbs(breadcrumbs)
                if (!hasRage && breadcrumbs?.any { (it as? JsonObject)?.let(::isRageBreadcrumb) == true } == true) {
                    hasRage = true
                }
                if (viewport != null && connection != null && hasRage) break
            }

            ReplayContextEnrichment(viewport = viewport, connection = connection, hasRage = hasRage)
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch replay context enrichment for project ${window.projectId}" }
            ReplayContextEnrichment()
        }
    }

    private suspend fun getReplayUserSessionCount(
        projectId: Long,
        startMs: Long,
        userId: String?,
        retentionDays: Int,
        demoEpochMs: Long? = null
    ): Int? {
        val user = userId?.takeIf { it.isNotBlank() } ?: return null
        val query =
            """
            SELECT countDistinct(replay_id) as count
            FROM `$clickhouseDb`.replay_events
            WHERE project_id = $projectId
                AND user_id = '${escapeSql(user)}'
                AND replay_start_timestamp < fromUnixTimestamp64Milli($startMs)
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = queryHelper.extractClickHouseBody(response) ?: return null
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            val obj = json.parseToJsonElement(line).jsonObject
            obj["count"]?.jsonPrimitive?.intOrNull
        }.getOrElse { e ->
            logger.error(e) { "Failed to count replay sessions for user in project $projectId" }
            null
        }
    }

    private fun replayWindowOrNull(
        projectId: Long,
        startedMs: Long?,
        finishedMs: Long?,
        userId: String?,
        retentionOptions: ReplayRetentionOptions
    ): ReplayWindowQuery? {
        val start = startedMs ?: return null
        val end = finishedMs ?: return null
        return ReplayWindowQuery(
            projectId = projectId,
            startMs = start,
            endMs = end,
            userId = userId,
            retentionDays = retentionOptions.retentionDays,
            demoEpochMs = retentionOptions.demoEpochMs
        )
    }

    private fun parseReplayTags(tagsStr: String): Map<String, String> =
        runCatching {
            (json.parseToJsonElement(tagsStr) as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.content }
                ?: emptyMap()
        }.getOrElse {
            emptyMap()
        }

    private suspend fun fallbackReplayErrorIds(
        replayErrorIds: List<String>,
        window: ReplayWindowQuery?
    ): List<String> =
        if (replayErrorIds.isEmpty() && window != null) {
            getReplayWindowErrorIds(window)
        } else {
            emptyList()
        }

    private suspend fun fallbackReplayErrorCount(
        replayErrorIds: List<String>,
        window: ReplayWindowQuery?
    ): Int =
        if (replayErrorIds.isEmpty() && window != null) {
            getReplayWindowErrorCount(window)
        } else {
            0
        }

    private suspend fun contextEnrichmentForWindow(window: ReplayWindowQuery?): ReplayContextEnrichment =
        window?.let { getReplayContextEnrichment(it) } ?: ReplayContextEnrichment()

    private suspend fun userSessionCountForWindow(window: ReplayWindowQuery?): Int? =
        window?.let {
            getReplayUserSessionCount(
                projectId = it.projectId,
                startMs = it.startMs,
                userId = it.userId,
                retentionDays = it.retentionDays,
                demoEpochMs = it.demoEpochMs
            )
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
        return suspendRunCatching {
            parsedToJsonElementList(json.parseToJsonElement(payload))
        }.getOrElse { e ->
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
        return suspendRunCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            obj["segment_id"]?.jsonPrimitive?.intOrNull
        }.getOrElse { _ ->
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
        if (payloadBytes.size < MP4_HEADER_MIN_BYTES) return false
        val boxType = String(payloadBytes.copyOfRange(MP4_BOX_TYPE_OFFSET, MP4_HEADER_MIN_BYTES), Charsets.US_ASCII)
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
        return suspendRunCatching {
            val unpacker = MessagePack.newDefaultUnpacker(rawBytes)
            val topMapSize = unpacker.unpackMapHeader()
            val events = mutableListOf<JsonElement>()
            val mobileSegmentIdHolder = mutableListOf<Int?>(null)

            repeat(topMapSize) {
                processMsgpackKey(unpacker, segmentIdx, events, mobileSegmentIdHolder)
            }

            unpacker.close()
            SegmentDecodeResult(events = events, isMobileReplay = true)
        }.getOrElse { e ->
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
    ): List<ReplayListItem> =
        getReplays(
            ReplayListQuery(
                scope = ServiceQueryScope.service(projectId),
                retentionDays = queryHelper.getProjectRetentionDays(projectId),
                page = page,
                limit = limit,
                environment = environment,
                period = period,
                demoEpochMs = demoEpochMs
            )
        )

    suspend fun getReplaysForServices(
        organizationId: Int,
        serviceIds: List<Long>,
        page: Int = 1,
        limit: Int = 25,
        environment: String? = null,
        period: String = "7d",
        demoEpochMs: Long? = null
    ): List<ReplayListItem> =
        getReplays(
            ReplayListQuery(
                scope = ServiceQueryScope.services(serviceIds),
                retentionDays = queryHelper.getOrganizationRetentionDays(organizationId),
                page = page,
                limit = limit,
                environment = environment,
                period = period,
                demoEpochMs = demoEpochMs
            )
        )

    private suspend fun getReplays(queryOptions: ReplayListQuery): List<ReplayListItem> {
        if (queryOptions.scope.serviceIds.isEmpty()) return emptyList()
        val offset = (queryOptions.page - 1) * queryOptions.limit
        val projectIdClause = queryOptions.scope.projectIdClause()

        val nowMs = queryOptions.demoEpochMs ?: System.currentTimeMillis()
        val periodMs =
            when (queryOptions.period) {
                "24h" -> PERIOD_24H_MS
                "30d" -> PERIOD_30D_MS
                "90d" -> PERIOD_90D_MS
                else -> PERIOD_7D_MS
            }
        val periodStartMs = nowMs - periodMs
        val retentionStartMs = nowMs - (queryOptions.retentionDays * MILLIS_PER_DAY)

        val envClause =
            if (queryOptions.environment != null && queryOptions.environment.isNotBlank()) {
                "AND environment = '${escapeSql(queryOptions.environment)}'"
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
            LIMIT ${queryOptions.limit} OFFSET $offset
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val rows = queryHelper.executeJsonEachRowQuery(query, "Replays") ?: return emptyList()
            rows.mapNotNull { obj ->
                suspendRunCatching {
                    val startedMs = obj["started_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val finishedMs = obj["finished_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val rowProjectId =
                        obj["project_id"]?.jsonPrimitive?.long ?: queryOptions.scope.serviceIds.firstOrNull()
                    val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
                    val retentionOptions = ReplayRetentionOptions(
                        retentionDays = queryOptions.retentionDays,
                        demoEpochMs = queryOptions.demoEpochMs
                    )
                    val replayWindow = rowProjectId?.let {
                        replayWindowOrNull(
                            projectId = it,
                            startedMs = startedMs,
                            finishedMs = finishedMs,
                            userId = userId,
                            retentionOptions = retentionOptions
                        )
                    }
                    val rawErrorCount = obj["error_count"]?.jsonPrimitive?.intOrNull ?: 0
                    val fallbackErrorCount =
                        if (rawErrorCount == 0) fallbackReplayErrorCount(emptyList(), replayWindow) else 0
                    val hasRage = contextEnrichmentForWindow(replayWindow).hasRage
                    buildReplayListItemFromJson(
                        obj,
                        rowProjectId ?: 0L,
                        maxOf(rawErrorCount, fallbackErrorCount),
                        hasRage
                    )
                }.getOrElse { e ->
                    logger.error(e) { "Failed to parse replay list row" }
                    null
                }
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch replays for services ${queryOptions.scope.cacheKeyPart()}" }
            emptyList()
        }
    }

    private suspend fun buildReplayDetailFromRow(
        obj: JsonObject,
        retentionDays: Int,
        demoEpochMs: Long? = null
    ): ReplayDetailResponse? {
        val replayId = obj["replay_id"]?.jsonPrimitive?.content ?: return null
        val objProjectId = obj["project_id"]?.jsonPrimitive?.long ?: return null
        val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
        val startedMs = obj["started_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val finishedMs = obj["finished_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val replayWindow = replayWindowOrNull(
            projectId = objProjectId,
            startedMs = startedMs,
            finishedMs = finishedMs,
            userId = userId,
            retentionOptions = ReplayRetentionOptions(retentionDays = retentionDays, demoEpochMs = demoEpochMs)
        )
        val urls = parseStringArray(obj["urls"])
        val tagsStr = obj["tags"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val tagsMap = parseReplayTags(tagsStr)
        val replayErrorIds = parseStringArray(obj["error_ids"]).distinct()
        val fallbackErrorIds = fallbackReplayErrorIds(replayErrorIds, replayWindow)
        val mergedErrorIds = (replayErrorIds + fallbackErrorIds).distinct()
        val fallbackErrorCount = fallbackReplayErrorCount(replayErrorIds, replayWindow)
        val contextEnrichment = contextEnrichmentForWindow(replayWindow)
        val activity = obj["activity"]?.jsonPrimitive?.intOrNull ?: 0
        val errorCount = maxOf(mergedErrorIds.size, fallbackErrorCount)
        val ipAddress = obj["user_ip_address"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val userSessionCount = userSessionCountForWindow(replayWindow)
        return ReplayDetailResponse(
            replayId = replayId,
            projectId = objProjectId,
            projectResourceId = projectResourceId(objProjectId),
            startedAt = obj["started_at"]?.jsonPrimitive?.content ?: "",
            finishedAt = obj["finished_at"]?.jsonPrimitive?.content ?: "",
            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            urls = urls,
            errorCount = errorCount,
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
            activity = activity,
            tags = tagsMap,
            signals = buildReplaySignals(errorCount, activity, contextEnrichment.hasRage),
            entryUrl = firstEntryUrl(urls),
            ipAddress = ipAddress,
            geo = geoLabelForIp(ipAddress),
            viewport = contextEnrichment.viewport,
            connection = contextEnrichment.connection,
            userSessionCount = userSessionCount
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
                argMax(user_ip_address, timestamp) as user_ip_address,
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

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = queryHelper.extractClickHouseBody(response) ?: return null
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            val obj = json.parseToJsonElement(line).jsonObject
            buildReplayDetailFromRow(obj, retentionDays, demoEpochMs)
        }.getOrElse { e ->
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
        suspendRunCatching {
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
        }.onFailure { e ->
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
            contexts,
            tags['http.status_code'] as http_status_code
        FROM `$clickhouseDb`.events e
        WHERE e.project_id = $projectId
            AND e.event_type = 'transaction'
            AND ($traceConditions)
            AND ${queryHelper.timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
        FORMAT JSONEachRow
        """.trimIndent()

    private fun getOrganizationIdForProject(projectId: Long): Int? =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Projects.organization_id)
        }

    private fun buildSpansByTraceQuery(
        projectId: Long,
        orgId: Int,
        traceIdList: String,
        retentionDays: Int,
        demoEpochMs: Long?
    ): String = """
        SELECT
            span_id_hex as span_id,
            trace_id_hex as trace_id,
            meta['sentry.transaction_id'] as transaction_id,
            resource as description,
            type as op,
            toUnixTimestamp64Milli(start) as start_ts_ms,
            duration / 1000000.0 as duration_ms,
            meta['http.status_code'] as http_status_code
        FROM `$clickhouseDb`.apm_spans
        WHERE organization_id = $orgId
            AND trace_id_hex IN ($traceIdList)
            AND meta['sentry.project_id'] = '$projectId'
            AND source = 'sentry'
            AND ${queryHelper.timestampRetentionClause("start", retentionDays, demoEpochMs)}
        FORMAT JSONEachRow
    """.trimIndent()

    private fun buildBreadcrumbsInRangeQuery(window: ReplayWindowQuery): String =
        """
        SELECT
            toString(event_id) as event_id,
            breadcrumbs
        FROM `$clickhouseDb`.events
        WHERE project_id = ${window.projectId}
            AND timestamp >= fromUnixTimestamp64Milli(${window.startMs})
            AND timestamp <= fromUnixTimestamp64Milli(${window.endMs})
            AND breadcrumbs != '[]'
            AND ${queryHelper.timestampRetentionClause("timestamp", window.retentionDays, window.demoEpochMs)}
            ${window.userClause()}
        ORDER BY timestamp ASC
        LIMIT $REPLAY_BREADCRUMB_EVENT_LIMIT
        FORMAT JSONEachRow
        """.trimIndent()

    private suspend fun fetchAndAddBreadcrumbTimelineItems(
        query: String,
        replayStartMs: Long,
        items: MutableList<ReplayTimelineItem>,
        addedIds: MutableSet<String>
    ) {
        suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (isClickHouseError(response.status.value, body, "Replay timeline breadcrumbs")) return

            body
                .lines()
                .filter { it.isNotBlank() }
                .filter { !it.trimStart().startsWith("Code:") }
                .forEach { line ->
                    val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    val eventId = stringValue(row, "event_id") ?: "event"
                    val breadcrumbs = parseStoredJsonArray(stringValue(row, "breadcrumbs")) ?: return@forEach
                    breadcrumbs.forEachIndexed { index, element ->
                        val breadcrumb = element as? JsonObject ?: return@forEachIndexed
                        val item = mapBreadcrumbTimelineItem(eventId, index, breadcrumb, replayStartMs)
                            ?: return@forEachIndexed
                        if (addedIds.add(item.id)) items.add(item)
                    }
                }
        }.onFailure { e ->
            logger.error(e) { "Failed to fetch replay timeline breadcrumbs" }
        }
    }

    private fun mapBreadcrumbTimelineItem(
        eventId: String,
        index: Int,
        breadcrumb: JsonObject,
        replayStartMs: Long
    ): ReplayTimelineItem? {
        if (shouldSkipBreadcrumb(breadcrumb)) return null
        val timestampMs = breadcrumbTimestampMs(breadcrumb) ?: return null
        val category = stringValue(breadcrumb, "category") ?: stringValue(breadcrumb, "type") ?: "breadcrumb"
        val data = breadcrumb["data"] as? JsonObject
        val message = stringValue(breadcrumb, "message")
        val title = breadcrumbTitle(category, message)
        val description = breadcrumbDescription(category, breadcrumb, data)
        val statusCode =
            statusCodeFromJson(data ?: JsonObject(emptyMap()), "status_code", "status") ?: statusCodeFromText(
                listOfNotNull(description, message, title).joinToString(" ")
            )

        return ReplayTimelineItem(
            id = "breadcrumb-$eventId-$index-$timestampMs",
            type = "span",
            timestamp = Instant.ofEpochMilli(timestampMs).toString(),
            offsetMs = (timestampMs - replayStartMs).toDouble(),
            title = title,
            description = description,
            category = category,
            statusCode = statusCode,
            rage = isRageBreadcrumb(breadcrumb).takeIf { it }
        )
    }

    private fun shouldSkipBreadcrumb(breadcrumb: JsonObject): Boolean {
        val category = stringValue(breadcrumb, "category") ?: ""
        if (category.startsWith("device.")) return true
        val data = breadcrumb["data"] as? JsonObject ?: return false
        val action = stringValue(data, "action") ?: return false
        return action in REPLAY_SKIPPED_BREADCRUMB_ACTIONS
    }

    private fun breadcrumbTimestampMs(breadcrumb: JsonObject): Long? {
        val primitive = breadcrumb["timestamp"] as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull ?: return null
        raw.toDoubleOrNull()?.let { return (it * MILLISECONDS_PER_SECOND).toLong() }
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun breadcrumbTitle(category: String, message: String?): String =
        if (category.isNotBlank() && category != "breadcrumb") {
            category
                .split(BREADCRUMB_CATEGORY_SPLIT_REGEX)
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.lowercase().replaceFirstChar { char -> char.uppercase() }
                }
        } else {
            message?.takeIf { it.isNotBlank() } ?: "Breadcrumb"
        }

    private fun breadcrumbDescription(
        category: String,
        breadcrumb: JsonObject,
        data: JsonObject?
    ): String? {
        stringValue(breadcrumb, "message")?.let { return it }
        val normalized = category.lowercase()
        if (normalized.contains("ui.lifecycle")) return lifecycleBreadcrumbDescription(data)
        if (normalized.contains("navigation")) return navigationBreadcrumbDescription(data)
        if (normalized.contains("http") || normalized.contains("network")) return networkBreadcrumbDescription(data)
        return firstStringValue(data ?: JsonObject(emptyMap()), "action", "type")
    }

    private fun lifecycleBreadcrumbDescription(data: JsonObject?): String? {
        val screen = data?.let { firstStringValue(it, "screen", "screen_name") } ?: "Screen"
        val state = data?.let { stringValue(it, "state") }.orEmpty()
        return "$screen: $state".trim()
    }

    private fun navigationBreadcrumbDescription(data: JsonObject?): String? {
        if (data == null) return null
        val screenName = firstStringValue(data, "screen_name", "screen")
        if (screenName != null) return screenName
        val from = stringValue(data, "from")
        val to = stringValue(data, "to")
        return listOfNotNull(from, to).takeIf { it.isNotEmpty() }?.joinToString(" -> ")
    }

    private fun networkBreadcrumbDescription(data: JsonObject?): String? {
        if (data == null) return null
        val parts =
            listOfNotNull(
                stringValue(data, "method"),
                firstStringValue(data, "url", "path"),
                firstStringValue(data, "status_code", "status")
            )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    suspend fun getReplayTimeline(
        replayId: String,
        demoEpochMs: Long? = null
    ): ReplayTimelineResponse {
        val replay = getReplay(replayId, demoEpochMs) ?: return ReplayTimelineResponse(emptyList(), 0L)
        val replayStartMs =
            suspendRunCatching {
                Instant.parse(replay.startedAt).toEpochMilli()
            }.getOrElse { _ ->
                return ReplayTimelineResponse(emptyList(), 0L)
            }
        val replayEndMs =
            suspendRunCatching {
                Instant.parse(replay.finishedAt).toEpochMilli()
            }.getOrElse { _ ->
                replayStartMs + MILLIS_PER_DAY
            }
        val projectId = replay.projectId
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionOptions = ReplayRetentionOptions(retentionDays = retentionDays, demoEpochMs = demoEpochMs)
        val replayWindow = ReplayWindowQuery(
            projectId = projectId,
            startMs = replayStartMs,
            endMs = replayEndMs,
            userId = replay.user?.id,
            retentionDays = retentionOptions.retentionDays,
            demoEpochMs = retentionOptions.demoEpochMs
        )
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

        val spansOrgId = getOrganizationIdForProject(projectId)

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

            if (spansOrgId != null) {
                val spansQuery = buildSpansByTraceQuery(projectId, spansOrgId, traceIdList, retentionDays, demoEpochMs)
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
                contexts,
                tags['http.status_code'] as http_status_code
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

        val spansInRangeQuery = if (spansOrgId != null) {
            """
            SELECT
                span_id_hex as span_id,
                trace_id_hex as trace_id,
                meta['sentry.transaction_id'] as transaction_id,
                resource as description,
                type as op,
                toUnixTimestamp64Milli(start) as start_ts_ms,
                duration / 1000000.0 as duration_ms,
                meta['http.status_code'] as http_status_code
            FROM `$clickhouseDb`.apm_spans
            WHERE organization_id = $spansOrgId
                AND meta['sentry.project_id'] = '$projectId'
                AND source = 'sentry'
                AND start >= fromUnixTimestamp64Milli($replayStartMs)
                AND start <= fromUnixTimestamp64Milli($replayEndMs)
                AND ${queryHelper.timestampRetentionClause("start", retentionDays, demoEpochMs)}
            ORDER BY start ASC
            LIMIT 200
            FORMAT JSONEachRow
            """.trimIndent()
        } else {
            ""
        }
        if (spansInRangeQuery.isNotBlank()) {
            fetchAndAddTimelineItems(
                query = spansInRangeQuery,
                errorContext = "Replay timeline spans by time range",
                failureMessage = "replay timeline spans by time range",
                replayStartMs = replayStartMs,
                mapper = ::mapSpanTimelineItem,
                items = items,
                addedIds = addedIds
            )
        }

        val breadcrumbsInRangeQuery =
            buildBreadcrumbsInRangeQuery(replayWindow)
        fetchAndAddBreadcrumbTimelineItems(
            query = breadcrumbsInRangeQuery,
            replayStartMs = replayStartMs,
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

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = queryHelper.extractClickHouseBody(response) ?: return null

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
        }.getOrElse { e ->
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

        return suspendRunCatching {
            val rows = queryHelper.executeJsonEachRowQuery(query, "Replays for issue") ?: return emptyList()
            rows.mapNotNull { obj ->
                suspendRunCatching {
                    buildReplayListItemFromJson(
                        obj,
                        projectId,
                        obj["error_count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }.getOrElse { e ->
                    logger.error(e) { "Failed to parse replay list row for issue" }
                    null
                }
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch replays for issue $issueId" }
            emptyList()
        }
    }

    companion object {
        private const val LOG_BODY_PREVIEW_LENGTH = 400
        private const val MP4_HEADER_MIN_BYTES = 8
        private const val MP4_BOX_TYPE_OFFSET = 4
        private const val MILLISECONDS_PER_SECOND = 1000.0
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        private const val PERIOD_24H_MS = MILLIS_PER_DAY
        private const val PERIOD_7D_MS = 7 * MILLIS_PER_DAY
        private const val PERIOD_30D_MS = 30 * MILLIS_PER_DAY
        private const val PERIOD_90D_MS = 90 * MILLIS_PER_DAY
        private const val REPLAY_CONTEXT_EVENT_LIMIT = 100
        private const val REPLAY_BREADCRUMB_EVENT_LIMIT = 100
        private val HTTP_STATUS_REGEX = Regex("""\b([1-5]\d{2})\b""")
        private val BREADCRUMB_CATEGORY_SPLIT_REGEX = Regex("[._-]+")
        private val REPLAY_SKIPPED_BREADCRUMB_ACTIONS =
            setOf("SCREEN_OFF", "SCREEN_ON", "DREAMING_STARTED", "DREAMING_STOPPED")
    }
}
