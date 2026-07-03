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

import com.moneat.events.models.ReplayRecordingDiagnosticsResponse
import com.moneat.events.models.ReplayRecordingSegmentDiagnostics
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class DecodedReplayRecordingSegment(
    val segmentId: Int?,
    val timestamp: String?,
    val timestampMs: Long?,
    val recordingDataBytes: Int,
    val events: List<JsonElement>,
    val isMobileReplay: Boolean
)

internal object ReplayRecordingDiagnosticsBuilder {
    fun build(segments: List<DecodedReplayRecordingSegment>): ReplayRecordingDiagnosticsResponse {
        val allEvents = segments.flatMap { it.events }
        val eventTypes = eventTypeCounts(allEvents)
        val timestamps = allEvents.mapNotNull(::eventTimestampMs)
        val hasMobileVideo = eventTypes.containsKey(MOBILE_REPLAY_VIDEO_TYPE)
        val hasFullSnapshot = eventTypes.containsKey(RRWEB_FULL_SNAPSHOT_TYPE)
        val hasIncrementalSnapshot = eventTypes.containsKey(RRWEB_INCREMENTAL_SNAPSHOT_TYPE)
        val isMobileReplay = segments.any { it.isMobileReplay } || hasMobileVideo
        val placeholderOnly =
            (isMobileReplay && allEvents.isEmpty()) || isPlaceholderOnly(eventTypes, allEvents.size)

        return ReplayRecordingDiagnosticsResponse(
            recordingSegmentCount = segments.size,
            decodedEventCount = allEvents.size,
            decodedSegmentIds = decodedSegmentIdsFromSegments(segments).map(::formatSegmentId),
            eventTypes = eventTypes,
            hasRrwebEvents = eventTypes.keys.any { it.toIntOrNull() != null },
            hasFullSnapshot = hasFullSnapshot,
            hasIncrementalSnapshot = hasIncrementalSnapshot,
            hasMobileVideo = hasMobileVideo,
            isMobileReplay = isMobileReplay,
            placeholderOnly = placeholderOnly,
            firstEventTimestampMs = timestamps.minOrNull(),
            lastEventTimestampMs = timestamps.maxOrNull(),
            segments = segments.map(::segmentDiagnostics),
            anomalies = recordingAnomalies(
                recordingSegmentCount = segments.size,
                decodedEventCount = allEvents.size,
                hasMobileVideo = hasMobileVideo,
                hasFullSnapshot = hasFullSnapshot,
                placeholderOnly = placeholderOnly
            )
        )
    }

    private fun segmentDiagnostics(segment: DecodedReplayRecordingSegment): ReplayRecordingSegmentDiagnostics {
        val eventTypes = eventTypeCounts(segment.events)
        return ReplayRecordingSegmentDiagnostics(
            segmentId = segment.segmentId?.let(::formatSegmentId),
            timestamp = segment.timestamp,
            timestampMs = segment.timestampMs,
            recordingDataBytes = segment.recordingDataBytes,
            decodedEventCount = segment.events.size,
            decodedSegmentIds = decodedSegmentIdsFromSegments(listOf(segment)).map(::formatSegmentId),
            eventTypes = eventTypes,
            hasFullSnapshot = eventTypes.containsKey(RRWEB_FULL_SNAPSHOT_TYPE),
            hasIncrementalSnapshot = eventTypes.containsKey(RRWEB_INCREMENTAL_SNAPSHOT_TYPE),
            hasMobileVideo = eventTypes.containsKey(MOBILE_REPLAY_VIDEO_TYPE),
            isMobileReplay = segment.isMobileReplay || eventTypes.containsKey(MOBILE_REPLAY_VIDEO_TYPE)
        )
    }

    private fun formatSegmentId(segmentId: Int): String = segmentId.toString()

    private fun eventTypeCounts(events: List<JsonElement>): Map<String, Int> =
        events
            .map(::eventTypeValue)
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

    private fun eventTypeValue(event: JsonElement): String {
        val obj = event as? JsonObject ?: return UNKNOWN_REPLAY_EVENT_TYPE
        val primitive = obj["type"] as? JsonPrimitive ?: return UNKNOWN_REPLAY_EVENT_TYPE
        return primitive.contentOrNull?.takeIf { it.isNotBlank() } ?: UNKNOWN_REPLAY_EVENT_TYPE
    }

    private fun decodedSegmentIds(events: List<JsonElement>): List<Int> =
        events
            .mapNotNull { event ->
                val obj = event as? JsonObject ?: return@mapNotNull null
                intValue(obj, "segment_id")
            }
            .distinct()
            .sorted()

    private fun decodedSegmentIdsFromSegments(segments: List<DecodedReplayRecordingSegment>): List<Int> =
        segments
            .filter { it.events.isNotEmpty() }
            .flatMap { segment ->
                val eventSegmentIds = decodedSegmentIds(segment.events)
                if (eventSegmentIds.isEmpty()) listOfNotNull(segment.segmentId) else eventSegmentIds
            }
            .distinct()
            .sorted()

    private fun eventTimestampMs(event: JsonElement): Long? {
        val obj = event as? JsonObject ?: return null
        return longValue(obj, "timestamp") ?: longValue(obj, "timestamp_ms")
    }

    private fun longValue(obj: JsonObject, key: String): Long? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        val content = primitive.contentOrNull ?: return null
        return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
    }

    private fun intValue(obj: JsonObject, key: String): Int? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    private fun isPlaceholderOnly(
        eventTypes: Map<String, Int>,
        decodedEventCount: Int
    ): Boolean =
        decodedEventCount == 1 &&
            eventTypes.size == 1 &&
            eventTypes[MOBILE_REPLAY_NOT_SUPPORTED_TYPE] == 1

    private fun recordingAnomalies(
        recordingSegmentCount: Int,
        decodedEventCount: Int,
        hasMobileVideo: Boolean,
        hasFullSnapshot: Boolean,
        placeholderOnly: Boolean
    ): List<String> =
        buildList {
            if (recordingSegmentCount == 0) add("recording_segments_missing")
            if (recordingSegmentCount > 0 && decodedEventCount == 0) {
                add("recording_segments_without_decoded_events")
            }
            if (placeholderOnly) add("mobile_replay_not_supported_placeholder")
            if (decodedEventCount > 0 && !hasMobileVideo && !hasFullSnapshot) {
                add("missing_full_snapshot")
            }
        }

    private const val RRWEB_FULL_SNAPSHOT_TYPE = "2"
    private const val RRWEB_INCREMENTAL_SNAPSHOT_TYPE = "3"
    private const val MOBILE_REPLAY_VIDEO_TYPE = "mobile_replay_video"
    private const val MOBILE_REPLAY_NOT_SUPPORTED_TYPE = "mobile_replay_not_supported"
    private const val UNKNOWN_REPLAY_EVENT_TYPE = "unknown"
}
