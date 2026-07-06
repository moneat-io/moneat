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

package com.moneat.datadog.routes

import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.models.DdReplaySegmentEvent
import com.moneat.datadog.reserveDatadogQuota
import com.moneat.datadog.services.DatadogReplayIngestRequest
import com.moneat.datadog.services.DatadogReplayIngestionService
import com.moneat.utils.suspendRunCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private const val REPLAY_EVENT_TYPE = "replay"
private const val DD_TAGS_QUERY_PARAM = "ddtags"
private const val DD_ENCODING_QUERY_PARAM = "dd-evp-encoding"
private const val DD_ORIGIN_VERSION_QUERY_PARAM = "dd-evp-origin-version"
private const val BYTES_PER_MEBIBYTE = 1024 * 1024
private const val MAX_REPLAY_MULTIPART_PART_MEBIBYTES = 50
internal const val MAX_REPLAY_MULTIPART_PART_BYTES =
    MAX_REPLAY_MULTIPART_PART_MEBIBYTES * BYTES_PER_MEBIBYTE

private val logger = KotlinLogging.logger {}
private val replayRouteJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

internal data class DatadogReplayUpload(
    val event: DdReplaySegmentEvent?,
    val eventBytes: Int,
    val segmentBytes: ByteArray?,
    val segmentEncoding: String?,
)

fun Route.datadogReplayRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/api/v2") {
        post("/replay") { handleDatadogReplayUpload(quotaService) }
    }

    route("/dd/api/v2") {
        post("/replay") { handleDatadogReplayUpload(quotaService) }
    }
}

private suspend fun RoutingContext.handleDatadogReplayUpload(
    quotaService: BillingQuotaService,
) {
    val context = DatadogAuthMiddleware.authenticateContext(call) ?: return
    val projectId = context.projectId?.toLong()
    if (projectId == null) {
        call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Project-scoped Datadog API key required for replay ingestion")
        )
        return
    }

    if (call.request.contentType().withoutParameters() != ContentType.MultiPart.FormData) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Multipart replay payload required"))
        return
    }

    val upload = suspendRunCatching {
        receiveDatadogReplayUpload()
    }.getOrElse { error ->
        logger.warn(error) { "Failed to parse Datadog replay multipart upload" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid replay upload"))
        return
    }
    val event = upload.event
    val segmentBytes = upload.segmentBytes
    if (event == null || segmentBytes == null) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Replay event and segment parts are required"))
        return
    }

    val totalBytes = upload.eventBytes.toLong() + segmentBytes.size
    if (!reserveDatadogQuota(call, quotaService, context.organizationId, 1, REPLAY_EVENT_TYPE, totalBytes)) {
        return
    }

    val tags = parseDdTags(call.request.queryParameters[DD_TAGS_QUERY_PARAM]) +
        sdkTag(call.request.queryParameters[DD_ORIGIN_VERSION_QUERY_PARAM])
    val request = DatadogReplayIngestRequest(
        organizationId = context.organizationId,
        projectId = projectId,
        event = event,
        segmentBytes = segmentBytes,
        declaredEncoding = call.request.queryParameters[DD_ENCODING_QUERY_PARAM] ?: upload.segmentEncoding,
        tags = tags,
    )

    val result = suspendRunCatching {
        DatadogReplayIngestionService.ingestReplaySegment(request)
    }.getOrElse { error ->
        if (error is IllegalArgumentException) {
            logger.warn(error) { "Datadog replay upload rejected" }
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Invalid replay payload")))
        } else {
            logger.error(error) { "Failed to ingest Datadog replay upload" }
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to ingest replay payload"))
        }
        return
    }

    call.respond(
        HttpStatusCode.Accepted,
        mapOf(
            "status" to "ok",
            "replay_id" to result.replayId,
            "segment_id" to result.segmentId.toString(),
            "records" to result.recordCount.toString(),
        )
    )
}

private suspend fun RoutingContext.receiveDatadogReplayUpload(): DatadogReplayUpload {
    var event: DdReplaySegmentEvent? = null
    var eventBytes = 0
    var segmentBytes: ByteArray? = null
    var segmentEncoding: String? = null

    call.receiveMultipart(MAX_REPLAY_MULTIPART_PART_BYTES.toLong()).forEachPart { part ->
        try {
            when {
                part.name == "event" && part is PartData.FormItem -> {
                    part.requireContentLengthWithinLimit()
                    val bytes = part.value.toByteArray()
                    requirePartWithinLimit("event", bytes.size.toLong())
                    eventBytes = bytes.size
                    event = parseReplayEvent(part.value)
                }
                part.name == "event" && part is PartData.FileItem -> {
                    part.requireContentLengthWithinLimit()
                    val bytes = part.provider().toByteArray()
                    requirePartWithinLimit("event", bytes.size.toLong())
                    eventBytes = bytes.size
                    event = parseReplayEvent(bytes.decodeToString())
                }
                part.name == "segment" && part is PartData.FileItem -> {
                    part.requireContentLengthWithinLimit()
                    val bytes = part.provider().toByteArray()
                    requirePartWithinLimit("segment", bytes.size.toLong())
                    segmentBytes = bytes
                    segmentEncoding = part.headers[HttpHeaders.ContentEncoding]
                }
            }
        } finally {
            part.release()
        }
    }

    return DatadogReplayUpload(
        event = event,
        eventBytes = eventBytes,
        segmentBytes = segmentBytes,
        segmentEncoding = segmentEncoding,
    )
}

private fun PartData.requireContentLengthWithinLimit() {
    val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return
    requirePartWithinLimit(name ?: "multipart", declaredLength)
}

internal fun requirePartWithinLimit(partName: String, bytes: Long) {
    require(bytes <= MAX_REPLAY_MULTIPART_PART_BYTES) {
        "Replay $partName part exceeds $MAX_REPLAY_MULTIPART_PART_BYTES byte limit"
    }
}

private fun parseReplayEvent(value: String): DdReplaySegmentEvent =
    try {
        replayRouteJson.decodeFromString<DdReplaySegmentEvent>(value)
    } catch (error: SerializationException) {
        logger.warn { "Failed to parse Datadog replay event JSON: ${error.message}" }
        throw IllegalArgumentException("Invalid replay event JSON", error)
    }

internal fun parseDdTags(value: String?): Map<String, String> {
    if (value.isNullOrBlank()) return emptyMap()
    return value.split(",")
        .mapNotNull { part ->
            val colonIdx = part.indexOf(':')
            if (colonIdx <= 0) return@mapNotNull null
            val key = part.substring(0, colonIdx).trim()
            val tagValue = part.substring(colonIdx + 1).trim()
            if (key.isEmpty()) null else key to tagValue
        }
        .toMap()
}

private fun sdkTag(value: String?): Map<String, String> =
    value
        ?.takeIf { it.isNotBlank() }
        ?.let { mapOf("sdk_version" to it) }
        ?: emptyMap()
