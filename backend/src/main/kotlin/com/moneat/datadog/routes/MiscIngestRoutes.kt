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

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DdContainerImagePayload
import com.moneat.datadog.models.DdDataLineagePayload
import com.moneat.datadog.models.DdDataStreamsPayload
import com.moneat.datadog.models.DdPipelineStatsPayload
import com.moneat.datadog.models.DdSbomPayload
import com.moneat.datadog.models.DdSymbolDbPayload
import com.moneat.datadog.models.DdSyntheticsPayload
import com.moneat.datadog.services.MiscIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun Route.miscIngestRoutes() {
    route("/dd") {
        // Symbol DB
        route("/symdb/v1") {
            post("/input") { handleSymbolDb() }
        }

        // Pipeline stats
        route("/v0.1") {
            post("/pipeline_stats") { handlePipelineStats() }
        }

        // Data lineage
        route("/api/v1") {
            post("/lineage") { handleDataLineage() }
        }

        // Data streams, synthetics, container images, SBOM
        route("/api/v2") {
            post("/data_streams") { handleDataStreams() }
            post("/synthetics") { handleSynthetics() }
            post("/contimage") { handleContainerImage() }
            post("/sbom") { handleSbom() }
        }
    }

    // The event platform forwarder strips the path from dd_url, so contlcycle/contimage/sbom
    // arrive at /api/v2/... (no /dd/ prefix) when redirected via datadog.yaml dd_url config.
    route("/api/v2") {
        post("/contlcycle") { handleContainerLifecycle() }
        post("/contimage") { handleContainerImage() }
        post("/sbom") { handleSbom() }
        post("/synthetics") { handleSynthetics() }
        post("/data_streams_messages") { handleDataStreams() }
        post("/events") { handleEventManagement() }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleSymbolDb() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdSymbolDbPayload>(body.decodeToString())
        MiscIngestionService.enqueueSymbolDb(orgId, payload)
        logger.debug { "Accepted DD symbol_db for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process symbol_db" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handlePipelineStats() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdPipelineStatsPayload>(body.decodeToString())
        val count = MiscIngestionService.enqueuePipelineStats(orgId, payload)
        logger.debug { "Accepted $count DD pipeline_stats for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process pipeline_stats" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDataLineage() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdDataLineagePayload>(body.decodeToString())
        MiscIngestionService.enqueueDataLineage(orgId, payload)
        logger.debug { "Accepted DD data_lineage for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process data_lineage" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDataStreams() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdDataStreamsPayload>(body.decodeToString())
        val count = MiscIngestionService.enqueueDataStreams(orgId, payload)
        logger.debug { "Accepted $count DD data_streams for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process data_streams" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleSynthetics() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdSyntheticsPayload>(body.decodeToString())
        val count = MiscIngestionService.enqueueSynthetics(orgId, payload)
        logger.debug { "Accepted $count DD synthetics for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process synthetics" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleContainerImage() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentType = call.request.headers["Content-Type"] ?: ""
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        if (contentType.contains("protobuf")) {
            // Agent sends application/x-protobuf; full protobuf pipeline not yet implemented.
            logger.debug { "Accepted DD contimage (protobuf) for org $orgId" }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            return
        }
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdContainerImagePayload>(body.decodeToString())
        MiscIngestionService.enqueueContainerImage(orgId, payload)
        logger.debug { "Accepted DD contimage for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process contimage" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleSbom() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentType = call.request.headers["Content-Type"] ?: ""
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        if (contentType.contains("protobuf")) {
            // Agent sends application/x-protobuf; full protobuf pipeline not yet implemented.
            logger.debug { "Accepted DD sbom (protobuf) for org $orgId" }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            return
        }
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdSbomPayload>(body.decodeToString())
        val count = MiscIngestionService.enqueueSbom(orgId, payload)
        logger.debug { "Accepted $count DD sbom packages for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process sbom" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleContainerLifecycle() {
    DatadogAuthMiddleware.authenticate(call) ?: return
    // Container lifecycle events accepted; full ingestion pipeline not yet implemented.
    call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleEventManagement() {
    DatadogAuthMiddleware.authenticate(call) ?: return
    // Event management events accepted; full ingestion pipeline not yet implemented.
    call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
}
