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
import com.moneat.datadog.DatadogMetricBillingRequest
import com.moneat.datadog.admitDatadogBatchWithQuotaRefund
import com.moneat.datadog.auth.DatadogAuthContext
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.ingest.DecompressionService
import com.moneat.datadog.decompression.MetricPayloadDecoder
import com.moneat.datadog.decompression.SketchPayloadDecoder
import com.moneat.datadog.metricSeriesBillingRequest
import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogSketchPayload
import com.moneat.datadog.reserveDatadogQuotaBatch
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.sketchBillingRequest
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun Route.datadogMetricRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd") {
        route("/api/v1") {
            post("/series") {
                handleV1MetricSeries(quotaService)
            }

            post("/sketches") {
                handleSketches(quotaService)
            }
        }

        route("/api/beta") {
            post("/sketches") {
                handleSketches(quotaService)
            }
        }

        route("/api/v3") {
            post("/series") {
                handleMetricSeries(quotaService, "V3")
            }

            post("/sketches") {
                handleSketches(quotaService)
            }
        }

        route("/api/v2") {
            post("/series") {
                handleMetricSeries(quotaService, "V2")
            }
        }
    }
}

private suspend fun RoutingContext.handleV1MetricSeries(
    quotaService: BillingQuotaService,
) {
    val authContext = DatadogAuthMiddleware.authenticateContext(call) ?: return
    val body = receiveDecompressedBody()
    val payload = decodeV1MetricSeriesJson(body) ?: return

    acceptMetricSeries(quotaService, authContext, body, payload, "V1")
}

private suspend fun RoutingContext.handleMetricSeries(
    quotaService: BillingQuotaService,
    apiVersion: String
) {
    val authContext = DatadogAuthMiddleware.authenticateContext(call) ?: return
    val contentType = call.request.contentType().toString()
    val body = receiveDecompressedBody()
    val payload = decodeMetricSeriesPayload(body, contentType, apiVersion) ?: return

    acceptMetricSeries(quotaService, authContext, body, payload, apiVersion)
}

private suspend fun RoutingContext.acceptMetricSeries(
    quotaService: BillingQuotaService,
    authContext: DatadogAuthContext,
    body: ByteArray,
    payload: DatadogMetricSeriesV1,
    apiVersion: String
) {
    val orgId = authContext.organizationId
    val billingRequest = metricSeriesBillingRequest(payload, body.size.toLong())
    if (!reserveMetricQuota(quotaService, orgId, billingRequest)) return

    val count = admitDatadogBatchWithQuotaRefund(
        quotaService = quotaService,
        organizationId = orgId,
        requestedUnitsByType = billingRequest.requestedUnitsByType,
        requestedBytesByType = billingRequest.requestedBytesByType,
    ) {
        DatadogMetricService.enqueueMetrics(
            organizationId = orgId.toLong(),
            payload = payload,
            projectId = authContext.projectId?.toLong(),
        )
    }
    logger.debug { "Accepted $count DD $apiVersion metrics for org $orgId" }
    call.respondAccepted()
}

private suspend fun RoutingContext.decodeV1MetricSeriesJson(
    body: ByteArray
): DatadogMetricSeriesV1? {
    return decodeMetricSeriesPayload(body, "application/json", "V1")
}

private suspend fun RoutingContext.decodeMetricSeriesPayload(
    body: ByteArray,
    contentType: String,
    apiVersion: String
): DatadogMetricSeriesV1? {
    return suspendRunCatching {
        decodeMetricSeriesBody(body, contentType)
    }.getOrElse { e ->
        logger.warn(e) { "Failed to parse DD $apiVersion series payload" }
        call.respondInvalidPayload()
        null
    }
}

private fun decodeMetricSeriesBody(
    body: ByteArray,
    contentType: String
): DatadogMetricSeriesV1 {
    return if (contentType.contains("application/json")) {
        json.decodeFromString(body.decodeToString())
    } else {
        MetricPayloadDecoder.decode(body)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleSketches(
    quotaService: BillingQuotaService,
) {
    val authContext = DatadogAuthMiddleware.authenticateContext(call) ?: return
    val orgId = authContext.organizationId

    val contentType = call.request.contentType().toString()
    val body = receiveDecompressedBody()

    if (body.isEmpty()) {
        call.respondAccepted()
        return
    }

    val payload = suspendRunCatching {
        if (contentType.contains("application/x-protobuf")) {
            SketchPayloadDecoder.decode(body)
        } else {
            val bodyStr = body.decodeToString()
            json.decodeFromString<DatadogSketchPayload>(bodyStr)
        }
    }.getOrElse { e ->
        logger.warn(e) { "Failed to parse DD sketches" }
        call.respondInvalidPayload()
        return
    }

    val billingRequest = sketchBillingRequest(payload, body.size.toLong())
    if (!reserveMetricQuota(quotaService, orgId, billingRequest)) return

    val count = admitDatadogBatchWithQuotaRefund(
        quotaService = quotaService,
        organizationId = orgId,
        requestedUnitsByType = billingRequest.requestedUnitsByType,
        requestedBytesByType = billingRequest.requestedBytesByType,
    ) {
        DatadogMetricService.enqueueSketches(
            organizationId = orgId.toLong(),
            payload = payload,
            projectId = authContext.projectId?.toLong(),
        )
    }

    logger.debug {
        "Accepted $count DD sketches " +
            "for org $orgId"
    }

    call.respondAccepted()
}

private suspend fun RoutingContext.receiveDecompressedBody(): ByteArray {
    val contentEncoding = call.request.headers["Content-Encoding"]
    val rawBody = call.receive<ByteArray>()
    return DecompressionService.decompress(rawBody, contentEncoding)
}

private suspend fun RoutingContext.reserveMetricQuota(
    quotaService: BillingQuotaService,
    orgId: Int,
    billingRequest: DatadogMetricBillingRequest,
): Boolean {
    return reserveDatadogQuotaBatch(
        call,
        quotaService,
        orgId,
        billingRequest.requestedUnitsByType,
        billingRequest.requestedBytesByType,
    )
}

private suspend fun ApplicationCall.respondInvalidPayload() {
    respond(HttpStatusCode.BadRequest, mapOf("errors" to listOf("Invalid payload")))
}

private suspend fun ApplicationCall.respondAccepted() {
    respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
}
