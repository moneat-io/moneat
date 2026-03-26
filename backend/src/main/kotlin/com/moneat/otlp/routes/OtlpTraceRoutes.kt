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

package com.moneat.otlp.routes

import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.otlp.OtlpAuth
import com.moneat.otlp.calculateBillableBytes
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpTraceService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.koin.core.context.GlobalContext

private val logger = KotlinLogging.logger {}
private const val DEFAULT_QUEUE_KEY = "moneat:otlp-traces:queue"

fun Route.otlpTraceRoutes(
    traceService: OtlpTraceService = OtlpTraceService(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
    otlpApiKeyService: OtlpApiKeyService = GlobalContext.get().get(),
) {
    route("/v1") {
        // Standard OTLP path
        post("/traces") {
            handleOtlpTraceIngest(call, traceService, quotaService, otlpApiKeyService)
        }
        // Moneat convention (matches /v1/logs/otlp)
        post("/traces/otlp") {
            handleOtlpTraceIngest(call, traceService, quotaService, otlpApiKeyService)
        }
    }
}

private suspend fun handleOtlpTraceIngest(
    call: io.ktor.server.application.ApplicationCall,
    traceService: OtlpTraceService,
    quotaService: BillingQuotaService,
    otlpApiKeyService: OtlpApiKeyService,
) {
    val contentType = call.request.header(HttpHeaders.ContentType) ?: ""
    val isJson = contentType.contains("application/json", ignoreCase = true)
    val isProtobuf = contentType.contains("application/x-protobuf", ignoreCase = true)
    if (!isJson && !isProtobuf) {
        call.respond(
            HttpStatusCode.UnsupportedMediaType,
            ErrorResponse(
                "OTLP traces endpoint requires Content-Type: application/json or application/x-protobuf."
            )
        )
        return
    }

    val organizationId: Int? = OtlpAuth.extractOrgId(call, otlpApiKeyService)

    if (organizationId == null) {
        call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("Missing or invalid OTLP API key")
        )
        return
    }

    val bodyBytes = call.receive<ByteArray>()
    val encoding = call.request.header(HttpHeaders.ContentEncoding)
    val payloadBytes = try {
        DecompressionService.decompress(bodyBytes, encoding)
    } catch (_: Exception) {
        throw BadRequestException("Failed to decompress request body")
    }

    val parsedSpans = if (isProtobuf) {
        traceService.parseOtlpTracesProtobuf(payloadBytes)
    } else {
        traceService.parseOtlpTracesJson(payloadBytes.decodeToString())
    } ?: throw BadRequestException("Invalid OTLP traces payload: malformed or missing resourceSpans")
    if (parsedSpans.isEmpty()) {
        call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
        return
    }

    if (quotaService.isEnforcementEnabled()) {
        val billableBytes = parsedSpans.calculateBillableBytes()
        val reservation = quotaService.reserveUnits(
            organizationId = organizationId,
            requestedUnits = parsedSpans.size,
            eventType = "otlp_trace",
            requestedBytes = billableBytes.toLong()
        )
        if (!reservation.allowed) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf(
                    "error" to "Quota exceeded",
                    "reason" to reservation.reason,
                    "usage" to reservation.usage
                )
            )
            return
        }
    }

    val queueKey = call.application.environment.config
        .propertyOrNull("otlp.tracesQueueKey")
        ?.getString()
        ?: DEFAULT_QUEUE_KEY
    val accepted = traceService.enqueueTraces(
        organizationId.toLong(),
        parsedSpans,
        queueKey
    )
    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
}
