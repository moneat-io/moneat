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
import com.moneat.config.RedisConfig
import com.moneat.datadog.reserveDatadogQuota
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.services.TraceIngestionService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.util.Base64

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private const val QUEUE_KEY = "moneat:traces:queue"

fun Route.traceIngestRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd") {
        // PUT /dd/v0.3/traces - v0.3 format (msgpack)
        put("/v0.3/traces") { handleTraceIntake(quotaService) }
        // PUT /dd/v0.4/traces - v0.4 format (msgpack, primary)
        put("/v0.4/traces") { handleTraceIntake(quotaService) }
        // PUT /dd/v0.5/traces - v0.5 format
        put("/v0.5/traces") { handleTraceIntake(quotaService) }
        // PUT /dd/v0.7/traces - v0.7 format
        put("/v0.7/traces") { handleTraceIntake(quotaService) }

        // PUT /dd/v0.6/stats - trace stats (local agent format)
        put("/v0.6/stats") { handleTraceStats(quotaService) }

        // DD agent backend/edge format - sent by dd-agent trace writer when forwarding to
        // the configured DD_APM_DD_URL (e.g. https://api.moneat.io/dd)
        route("/api") {
            post("/v0.2/traces") { handleTraceIntake(quotaService) }
            post("/v0.6/stats") { handleTraceStats(quotaService) }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleTraceIntake(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call)
        ?: return

    val rawBytes = call.receiveChannel().toByteArray()
    val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
    val hostname = call.request.headers["X-Datadog-Hostname"] ?: ""
    val env = call.request.headers["X-Datadog-Env"] ?: ""
    val version = call.request.headers["X-Datadog-Version"] ?: ""

    val contentType = call.request.contentType()
    val isJson = contentType.withoutParameters() == ContentType.Application.Json
    val isProtobuf = contentType.contentSubtype.contains("protobuf")
    val tracesJsonElement = if (isJson) {
        json.parseToJsonElement(bytes.decodeToString())
    } else {
        null
    }
    val requestedUnits = countTraceSpans(bytes, tracesJsonElement, isProtobuf)
    if (requestedUnits == null) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unparseable trace payload"))
        return
    }

    if (!reserveDatadogQuota(call, quotaService, organizationId, requestedUnits, "dd_trace", bytes.size.toLong())) {
        return
    }

    // Enqueue for async processing
    val queuePayload = when {
        isJson -> buildJsonObject {
            put("organization_id", organizationId)
            put("hostname", hostname)
            put("env", env)
            put("version", version)
            put("format", "json")
            put("traces", requireNotNull(tracesJsonElement))
        }.toString()
        isProtobuf -> buildJsonObject {
            put("organization_id", organizationId)
            put("hostname", hostname)
            put("env", env)
            put("version", version)
            put("format", "protobuf")
            put("data", Base64.getEncoder().encodeToString(bytes))
        }.toString()
        else -> buildJsonObject {
            put("organization_id", organizationId)
            put("hostname", hostname)
            put("env", env)
            put("version", version)
            put("format", "msgpack")
            put("data", Base64.getEncoder().encodeToString(bytes))
        }.toString()
    }

    RedisConfig.sync().lpush(QUEUE_KEY, queuePayload)

    // Return rate_by_service response (DD agent expects this)
    call.respond(
        HttpStatusCode.OK,
        mapOf("rate_by_service" to mapOf("service:,env:" to 1.0))
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleTraceStats(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call)
        ?: return

    val rawBytes = call.receiveChannel().toByteArray()
    val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
    val body = bytes.decodeToString()

    val payload = json.decodeFromString<
        com.moneat.datadog.models.DdStatsPayload
        >(body)
    val requestedUnits = payload.stats.sumOf { it.stats.size }
    if (!reserveDatadogQuota(call, quotaService, organizationId, requestedUnits, "dd_trace", bytes.size.toLong())) {
        return
    }

    TraceIngestionService.insertTraceStats(organizationId, payload)

    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
}

private fun countTraceSpans(
    bytes: ByteArray,
    tracesJsonElement: JsonElement?,
    isProtobuf: Boolean,
): Int? {
    if (tracesJsonElement != null) {
        return tracesJsonElement.jsonArray.sumOf { trace -> trace.jsonArray.size }
    }

    return runCatching {
        val traces = if (isProtobuf) {
            TraceIngestionService.parseProtobufAgentPayload(bytes)
        } else {
            TraceIngestionService.parseMsgpackTraces(bytes)
        }
        traces.sumOf { it.size }
    }.getOrElse { e ->
        logger.warn(e) { "Failed to parse trace payload for span count" }
        null
    }
}
