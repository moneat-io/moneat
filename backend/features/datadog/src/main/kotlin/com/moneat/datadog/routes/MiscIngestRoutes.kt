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
import com.moneat.datadog.reserveDatadogQuota
import com.moneat.datadog.admitDatadogWithQuotaRefund
import com.moneat.datadog.DatadogQuotaCharge
import com.moneat.datadog.rethrowIfQueueAdmission
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.ingest.DecompressionService
import com.moneat.datadog.decompression.MiscPayloadDecoder
import com.moneat.datadog.models.DatadogEventPayload
import com.moneat.datadog.models.DdContainerImagePayload
import com.moneat.datadog.models.DdDataLineagePayload
import com.moneat.datadog.models.DdDataStreamsPayload
import com.moneat.datadog.models.DdPipelineStatsPayload
import com.moneat.datadog.models.DdSbomPayload
import com.moneat.datadog.models.DdSymbolDbPayload
import com.moneat.datadog.models.DdSyntheticsPayload
import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.MiscIngestionService
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val CONTENT_ENCODING_HEADER = "Content-Encoding"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val INVALID_PAYLOAD_ERROR = "Invalid payload"

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun Route.miscIngestRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
    includeSbomRoutes: Boolean = true,
) {
    route("/dd") {
        // Symbol DB
        route("/symdb/v1") {
            post("/input") { handleSymbolDb(quotaService) }
        }

        // Pipeline stats
        route("/v0.1") {
            post("/pipeline_stats") { handlePipelineStats(quotaService) }
        }

        // Data lineage
        route("/api/v1") {
            post("/lineage") { handleDataLineage(quotaService) }
        }

        // Data streams, synthetics, container images, SBOM
        route("/api/v2") {
            post("/data_streams") { handleDataStreams(quotaService) }
            post("/synthetics") { handleSynthetics(quotaService) }
            post("/contimage") { handleContainerImage(quotaService) }
            if (includeSbomRoutes) {
                post("/sbom") { handleSbom(quotaService) }
            }
        }
    }

    // The event platform forwarder strips the path from dd_url, so contlcycle/contimage/sbom
    // arrive at /api/v2/... (no /dd/ prefix) when redirected via datadog.yaml dd_url config.
    route("/api/v2") {
        post("/contlcycle") { handleContainerLifecycle(quotaService) }
        post("/contimage") { handleContainerImage(quotaService) }
        if (includeSbomRoutes) {
            post("/sbom") { handleSbom(quotaService) }
        }
        post("/synthetics") { handleSynthetics(quotaService) }
        post("/data_streams_messages") { handleDataStreams(quotaService) }
        post("/events") { handleEventManagement(quotaService) }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleSymbolDb(quotaService: BillingQuotaService) =
    withDatadogPayload("symbol_db") { orgId, body ->
        val payload = json.decodeFromString<DdSymbolDbPayload>(body.text())
        if (!reserveMiscQuota(quotaService, orgId, 1, body.bytes)) return@withDatadogPayload
        admitMiscWithQuotaRefund(quotaService, orgId, 1, body.bytes) {
            MiscIngestionService.enqueueSymbolDb(orgId, payload)
        }
        logger.debug { "Accepted DD symbol_db for org $orgId" }
        respondAccepted()
    }

private suspend fun io.ktor.server.routing.RoutingContext.handlePipelineStats(quotaService: BillingQuotaService) =
    withDatadogPayload("pipeline_stats") { orgId, body ->
        val payload = json.decodeFromString<DdPipelineStatsPayload>(body.text())
        if (!reserveMiscQuota(quotaService, orgId, payload.stats.size, body.bytes)) return@withDatadogPayload
        val count = admitMiscWithQuotaRefund(quotaService, orgId, payload.stats.size, body.bytes) {
            MiscIngestionService.enqueuePipelineStats(orgId, payload)
        }
        logger.debug { "Accepted $count DD pipeline_stats for org $orgId" }
        respondAccepted()
    }

private suspend fun io.ktor.server.routing.RoutingContext.handleDataLineage(quotaService: BillingQuotaService) =
    withDatadogPayload("data_lineage") { orgId, body ->
        val payload = json.decodeFromString<DdDataLineagePayload>(body.text())
        if (!reserveMiscQuota(quotaService, orgId, 1, body.bytes)) return@withDatadogPayload
        admitMiscWithQuotaRefund(quotaService, orgId, 1, body.bytes) {
            MiscIngestionService.enqueueDataLineage(orgId, payload)
        }
        logger.debug { "Accepted DD data_lineage for org $orgId" }
        respondAccepted()
    }

private suspend fun io.ktor.server.routing.RoutingContext.handleDataStreams(quotaService: BillingQuotaService) =
    withDatadogPayload("data_streams") { orgId, body ->
        val payload = json.decodeFromString<DdDataStreamsPayload>(body.text())
        if (!reserveMiscQuota(quotaService, orgId, payload.stats.size, body.bytes)) return@withDatadogPayload
        val count = admitMiscWithQuotaRefund(quotaService, orgId, payload.stats.size, body.bytes) {
            MiscIngestionService.enqueueDataStreams(orgId, payload)
        }
        logger.debug { "Accepted $count DD data_streams for org $orgId" }
        respondAccepted()
    }

private suspend fun io.ktor.server.routing.RoutingContext.handleSynthetics(quotaService: BillingQuotaService) =
    withDatadogPayload("synthetics") { orgId, body ->
        val payload = json.decodeFromString<DdSyntheticsPayload>(body.text())
        if (!reserveMiscQuota(quotaService, orgId, payload.results.size, body.bytes)) return@withDatadogPayload
        val count = admitMiscWithQuotaRefund(quotaService, orgId, payload.results.size, body.bytes) {
            MiscIngestionService.enqueueSynthetics(orgId, payload)
        }
        logger.debug { "Accepted $count DD synthetics for org $orgId" }
        respondAccepted()
    }

private suspend fun io.ktor.server.routing.RoutingContext.handleContainerImage(quotaService: BillingQuotaService) =
    withDatadogPayload("contimage") { orgId, body ->
        if (body.isProtobuf) {
            val payloads = MiscPayloadDecoder.decodeContainerImages(body.bytes)
            if (!reserveMiscQuota(quotaService, orgId, payloads.size, body.bytes)) return@withDatadogPayload
            val count = admitMiscWithQuotaRefund(quotaService, orgId, payloads.size, body.bytes) {
                MiscIngestionService.enqueueContainerImages(orgId, payloads)
            }
            logger.debug { "Accepted $count DD contimage protobuf images for org $orgId" }
            respondAccepted()
            return@withDatadogPayload
        }
        val payload = json.decodeFromString<DdContainerImagePayload>(body.text())
        if (!reserveMiscQuota(quotaService, orgId, 1, body.bytes)) return@withDatadogPayload
        admitMiscWithQuotaRefund(quotaService, orgId, 1, body.bytes) {
            MiscIngestionService.enqueueContainerImage(orgId, payload)
        }
        logger.debug { "Accepted DD contimage for org $orgId" }
        respondAccepted()
    }

private suspend fun io.ktor.server.routing.RoutingContext.handleSbom(quotaService: BillingQuotaService) =
    withDatadogPayload("sbom") { orgId, body ->
        if (body.isProtobuf) {
            val payload = MiscPayloadDecoder.decodeSbom(body.bytes)
            if (!reserveMiscQuota(quotaService, orgId, payload.packages.size, body.bytes)) {
                return@withDatadogPayload
            }
            val count = admitMiscWithQuotaRefund(quotaService, orgId, payload.packages.size, body.bytes) {
                MiscIngestionService.enqueueSbom(orgId, payload)
            }
            logger.debug { "Accepted $count DD sbom protobuf packages for org $orgId" }
            respondAccepted()
            return@withDatadogPayload
        }
        val payload = json.decodeFromString<DdSbomPayload>(body.text())
        if (!reserveMiscQuota(quotaService, orgId, payload.packages.size, body.bytes)) return@withDatadogPayload
        val count = admitMiscWithQuotaRefund(quotaService, orgId, payload.packages.size, body.bytes) {
            MiscIngestionService.enqueueSbom(orgId, payload)
        }
        logger.debug { "Accepted $count DD sbom packages for org $orgId" }
        respondAccepted()
    }

private suspend fun io.ktor.server.routing.RoutingContext.handleContainerLifecycle(
    quotaService: BillingQuotaService,
) = withDatadogPayload("container lifecycle events") { orgId, body ->
    val events = if (body.isProtobuf) {
        MiscPayloadDecoder.decodeContainerLifecycleEvents(body.bytes)
    } else if (body.text().trim().isEmptyObject()) {
        emptyList()
    } else {
        throw IllegalArgumentException("Container lifecycle payload must be protobuf")
    }
    if (!reserveEventQuota(quotaService, orgId, events.size, body.bytes)) return@withDatadogPayload
    val count = admitEventWithQuotaRefund(
        quotaService,
        orgId,
        events.size,
        body.bytes,
    ) {
        DatadogEventService.enqueueEvents(orgId.toLong(), events)
    }
    logger.debug { "Accepted $count DD container lifecycle events for org $orgId" }
    respondAccepted()
}

private suspend fun io.ktor.server.routing.RoutingContext.handleEventManagement(
    quotaService: BillingQuotaService,
) = withDatadogPayload("event-management events") { orgId, body ->
    val payload = json.decodeFromString<DatadogEventPayload>(body.text())
    if (!reserveEventQuota(quotaService, orgId, payload.events.size, body.bytes)) return@withDatadogPayload
    val count = admitEventWithQuotaRefund(
        quotaService,
        orgId,
        payload.events.size,
        body.bytes,
    ) {
        DatadogEventService.enqueueEvents(orgId.toLong(), payload.events)
    }
    logger.debug { "Accepted $count DD event-management events for org $orgId" }
    respondAccepted()
}

private class MiscIngestBody(
    val bytes: ByteArray,
    val contentType: String,
) {
    val isProtobuf: Boolean
        get() = contentType.isProtobufContent()

    fun text(): String = bytes.decodeToString()
}

private suspend fun io.ktor.server.routing.RoutingContext.withDatadogPayload(
    failureName: String,
    block: suspend io.ktor.server.routing.RoutingContext.(Int, MiscIngestBody) -> Unit,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        block(orgId, receiveMiscIngestBody())
    }.getOrElse { e ->
        e.rethrowIfQueueAdmission()
        logger.error(e) { "Failed to process $failureName" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID_PAYLOAD_ERROR))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.receiveMiscIngestBody(): MiscIngestBody {
    val contentEncoding = call.request.headers[CONTENT_ENCODING_HEADER]
    val contentType = call.request.headers[CONTENT_TYPE_HEADER] ?: ""
    val rawBody = call.receive<ByteArray>()
    val body = DecompressionService.decompress(rawBody, contentEncoding)
    return MiscIngestBody(bytes = body, contentType = contentType)
}

private suspend fun io.ktor.server.routing.RoutingContext.respondAccepted() {
    call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
}

private suspend fun io.ktor.server.routing.RoutingContext.reserveMiscQuota(
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnits: Int,
    bytes: ByteArray,
): Boolean {
    return reserveDatadogQuota(
        call = call,
        quotaService = quotaService,
        organizationId = organizationId,
        requestedUnits = requestedUnits,
        eventType = "dd_misc",
        requestedBytes = bytes.size.toLong(),
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.reserveEventQuota(
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnits: Int,
    bytes: ByteArray,
): Boolean {
    return reserveDatadogQuota(
        call = call,
        quotaService = quotaService,
        organizationId = organizationId,
        requestedUnits = requestedUnits,
        eventType = "dd_event",
        requestedBytes = bytes.size.toLong(),
    )
}

private inline fun <T> admitMiscWithQuotaRefund(
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnits: Int,
    bytes: ByteArray,
    admit: () -> T,
): T =
    admitDatadogWithQuotaRefund(
        quotaService,
        DatadogQuotaCharge(organizationId, requestedUnits, "dd_misc", bytes.size.toLong()),
        admit,
    )

private inline fun <T> admitEventWithQuotaRefund(
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnits: Int,
    bytes: ByteArray,
    admit: () -> T,
): T =
    admitDatadogWithQuotaRefund(
        quotaService,
        DatadogQuotaCharge(organizationId, requestedUnits, "dd_event", bytes.size.toLong()),
        admit,
    )

private fun String.isProtobufContent(): Boolean =
    contains("protobuf", ignoreCase = true)

private val emptyJsonObjectRegex = Regex("""\{\s*}""")

private fun String.isEmptyObject(): Boolean =
    this == "null" || emptyJsonObjectRegex.matches(this)
