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
import com.moneat.billing.services.QuotaExceededResponse
import com.moneat.ingest.DecompressionService
import com.moneat.otlp.METRIC_BILLABLE_OVERHEAD_BYTES
import com.moneat.otlp.OtlpAuth
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpMetricInsert
import com.moneat.otlp.services.OtlpMetricsService
import com.moneat.otlp.services.OtlpServiceDescriptor
import com.moneat.otlp.services.OtlpServiceRoutingService
import com.moneat.otlp.services.OtlpSignalType
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}
private const val DEFAULT_QUEUE_KEY = "moneat:otlp-metrics:queue"

fun Route.otlpMetricsRoutes(
    metricsService: OtlpMetricsService = OtlpMetricsService(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
    otlpApiKeyService: OtlpApiKeyService = GlobalContext.get().get(),
    otlpServiceRoutingService: OtlpServiceRoutingService = GlobalContext.get().get(),
) {
    route("/v1") {
        post("/metrics") {
            handleOtlpMetricsIngest(
                call,
                metricsService,
                quotaService,
                otlpApiKeyService,
                otlpServiceRoutingService
            )
        }
        post("/metrics/otlp") {
            handleOtlpMetricsIngest(
                call,
                metricsService,
                quotaService,
                otlpApiKeyService,
                otlpServiceRoutingService
            )
        }
    }
}

private suspend fun handleOtlpMetricsIngest(
    call: io.ktor.server.application.ApplicationCall,
    metricsService: OtlpMetricsService,
    quotaService: BillingQuotaService,
    otlpApiKeyService: OtlpApiKeyService,
    otlpServiceRoutingService: OtlpServiceRoutingService,
) {
    val contentType = call.request.header(HttpHeaders.ContentType) ?: ""
    val isJson = contentType.contains("application/json", ignoreCase = true)
    val isProtobuf = contentType.contains("application/x-protobuf", ignoreCase = true)
    if (!isJson && !isProtobuf) {
        call.respond(
            HttpStatusCode.UnsupportedMediaType,
            ErrorResponse(
                "OTLP metrics endpoint requires Content-Type: application/json or application/x-protobuf."
            )
        )
        return
    }

    val organizationId = OtlpAuth.extractOrgId(call, otlpApiKeyService)

    if (organizationId == null) {
        call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("Missing or invalid OTLP API key")
        )
        return
    }

    val bodyBytes = call.receive<ByteArray>()
    val encoding = call.request.header(HttpHeaders.ContentEncoding)
    val payloadBytes = suspendRunCatching {
        DecompressionService.decompress(bodyBytes, encoding)
    }.getOrElse { _ ->
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Failed to decompress request body")
        )
        return
    }

    val parsedMetrics = if (isProtobuf) {
        metricsService.parseOtlpMetricsProtobuf(payloadBytes)
    } else {
        metricsService.parseOtlpMetricsJson(payloadBytes.decodeToString())
    }
    if (parsedMetrics == null) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Invalid OTLP metrics payload: malformed JSON or missing resourceMetrics")
        )
        return
    }
    if (parsedMetrics.isEmpty()) {
        call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
        return
    }

    if (quotaService.isEnforcementEnabled()) {
        val billableBytes =
            parsedMetrics.sumOf { it.metricName.length + METRIC_BILLABLE_OVERHEAD_BYTES }.toLong()
        val reservation = quotaService.reserveUnits(
            organizationId = organizationId,
            requestedUnits = parsedMetrics.size,
            eventType = "otlp_metric",
            requestedBytes = billableBytes
        )
        if (!reservation.allowed) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                QuotaExceededResponse(reason = reservation.reason, usage = reservation.usage)
            )
            return
        }
    }

    val queueKey = call.application.environment.config
        .propertyOrNull("otlp.metricsQueueKey")
        ?.getString()
        ?: DEFAULT_QUEUE_KEY
    val routedMetrics = routeMetrics(organizationId, parsedMetrics, otlpServiceRoutingService)
    val accepted = metricsService.enqueueMetrics(
        organizationId.toLong(),
        routedMetrics,
        queueKey
    )
    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
}

private fun routeMetrics(
    organizationId: Int,
    metrics: List<OtlpMetricInsert>,
    routingService: OtlpServiceRoutingService,
): List<OtlpMetricInsert> {
    val descriptors = metrics.map { metric ->
        OtlpServiceDescriptor(
            serviceNamespace = metric.serviceNamespace,
            serviceName = metric.service,
            environment = metric.env,
        )
    }
    val projectIds = routingService.resolveProjectIds(organizationId, descriptors, OtlpSignalType.METRICS)
    return metrics.map { metric ->
        val identity = routingService.normalizeIdentity(metric.serviceNamespace, metric.service)
        metric.copy(projectId = identity?.let { projectIds[it] })
    }
}
