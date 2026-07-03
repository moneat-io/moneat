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

package com.moneat.mcp.tools

import com.moneat.events.models.ReplayDetailResponse
import com.moneat.events.models.ReplayRecordingDiagnosticsResponse
import com.moneat.events.models.ReplayRecordingSegmentDiagnostics
import com.moneat.events.services.DashboardService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val replayDashboardService = DashboardService.create()

private const val DEFAULT_REPLAY_PAGE = 1
private const val DEFAULT_REPLAY_LIMIT = 25
private const val MAX_REPLAY_LIMIT = 100
private const val DEFAULT_REPLAY_PERIOD = "7d"
private const val REPLAY_ID = "replay_id"

class ListReplaysTool : McpTool {
    override val name = "list_replays"
    override val description = "List session replays for a project"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "project_id" to schemaProjectId(),
                "page" to schemaNumber("Page number (default 1)"),
                "limit" to schemaNumber("Results per page (default 25, max 100)"),
                "environment" to schemaString("Environment filter"),
                "period" to schemaString("Time period such as 24h, 7d, or 30d (default 7d)")
            )
        ),
        required = listOf("project_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult = withRequiredProjectId(args) { projectId ->
        val page = (args["page"]?.jsonPrimitive?.intOrNull ?: DEFAULT_REPLAY_PAGE).coerceAtLeast(1)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_REPLAY_LIMIT).coerceIn(1, MAX_REPLAY_LIMIT)
        val environment = args.stringContent("environment")
        val period = args.stringContent("period") ?: DEFAULT_REPLAY_PERIOD
        val replays = replayDashboardService.getReplays(projectId, page, limit, environment, period)
        jsonResult(replays)
    }
}

class GetReplayTool : McpTool {
    override val name = "get_replay"
    override val description = "Get session replay metadata and correlated telemetry identifiers"
    override val inputSchema = replayIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val replayId = args.replayIdArg() ?: return errorResult("$REPLAY_ID is required")
        val replay = replayDashboardService.getReplay(replayId)
            ?: return errorResult("Replay not found: $replayId")
        return jsonResult(replay)
    }
}

class GetReplayTimelineTool : McpTool {
    override val name = "get_replay_timeline"
    override val description = "Get correlated timeline items for a session replay"
    override val inputSchema = replayIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val replayId = args.replayIdArg() ?: return errorResult("$REPLAY_ID is required")
        return jsonResult(replayDashboardService.getReplayTimeline(replayId))
    }
}

class GetReplayRecordingSummaryTool : McpTool {
    override val name = "get_replay_recording_summary"
    override val description = "Summarize replay recording health without returning raw recording payloads"
    override val inputSchema = replayIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val replayId = args.replayIdArg() ?: return errorResult("$REPLAY_ID is required")
        val replay = replayDashboardService.getReplay(replayId)
            ?: return errorResult("Replay not found: $replayId")
        val diagnostics = replayDashboardService.getReplayRecordingDiagnostics(replayId)
            ?: return errorResult("Replay recording not found: $replayId")
        val timeline = replayDashboardService.getReplayTimeline(replayId)

        return jsonResult(
            ReplayRecordingSummaryToolResponse.from(
                replay = replay,
                diagnostics = diagnostics,
                timelineItemCount = timeline.items.size
            )
        )
    }
}

private fun replayIdInputSchema(): InputSchema = InputSchema(
    properties = JsonObject(
        mapOf(REPLAY_ID to schemaString("Replay ID"))
    ),
    required = listOf(REPLAY_ID)
)

private fun JsonObject.replayIdArg(): String? =
    stringContent(REPLAY_ID)

@Serializable
private data class ReplayRecordingSummaryToolResponse(
    val replayId: String,
    val projectId: String,
    val replayMetadataSegmentCount: Int,
    val recordingSegmentCount: Int,
    val decodedEventCount: Int,
    val decodedSegmentIds: List<String>,
    val eventTypes: Map<String, Int>,
    val timelineItemCount: Int,
    val hasRrwebEvents: Boolean,
    val hasFullSnapshot: Boolean,
    val hasIncrementalSnapshot: Boolean,
    val hasMobileVideo: Boolean,
    val isMobileReplay: Boolean,
    val placeholderOnly: Boolean,
    val firstEventTimestampMs: Long?,
    val lastEventTimestampMs: Long?,
    val segments: List<ReplayRecordingSegmentDiagnostics>,
    val anomalies: List<String>
) {
    companion object {
        fun from(
            replay: ReplayDetailResponse,
            diagnostics: ReplayRecordingDiagnosticsResponse,
            timelineItemCount: Int
        ): ReplayRecordingSummaryToolResponse =
            ReplayRecordingSummaryToolResponse(
                replayId = replay.replayId,
                projectId = replay.projectId,
                replayMetadataSegmentCount = replay.segmentCount,
                recordingSegmentCount = diagnostics.recordingSegmentCount,
                decodedEventCount = diagnostics.decodedEventCount,
                decodedSegmentIds = diagnostics.decodedSegmentIds,
                eventTypes = diagnostics.eventTypes,
                timelineItemCount = timelineItemCount,
                hasRrwebEvents = diagnostics.hasRrwebEvents,
                hasFullSnapshot = diagnostics.hasFullSnapshot,
                hasIncrementalSnapshot = diagnostics.hasIncrementalSnapshot,
                hasMobileVideo = diagnostics.hasMobileVideo,
                isMobileReplay = diagnostics.isMobileReplay,
                placeholderOnly = diagnostics.placeholderOnly,
                firstEventTimestampMs = diagnostics.firstEventTimestampMs,
                lastEventTimestampMs = diagnostics.lastEventTimestampMs,
                segments = diagnostics.segments,
                anomalies = diagnostics.anomalies
            )
    }
}
