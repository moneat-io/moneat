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
import com.moneat.otlp.OtlpAuth
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpFeedbackIngestResult
import com.moneat.otlp.services.OtlpFeedbackInsert
import com.moneat.otlp.services.OtlpFeedbackService
import com.moneat.otlp.services.OtlpServiceDescriptor
import com.moneat.otlp.services.OtlpServiceRoutingService
import com.moneat.otlp.services.OtlpSignalType
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

fun Route.otlpFeedbackRoutes(
    feedbackService: OtlpFeedbackService = OtlpFeedbackService(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
    otlpApiKeyService: OtlpApiKeyService = GlobalContext.get().get(),
    otlpServiceRoutingService: OtlpServiceRoutingService = GlobalContext.get().get(),
) {
    route("/v1") {
        post("/feedback/otlp") {
            handleOtlpFeedbackIngest(
                call,
                feedbackService,
                quotaService,
                otlpApiKeyService,
                otlpServiceRoutingService
            )
        }
    }
}

private suspend fun handleOtlpFeedbackIngest(
    call: ApplicationCall,
    feedbackService: OtlpFeedbackService,
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
                "OTLP feedback endpoint requires Content-Type: application/json or application/x-protobuf."
            )
        )
        return
    }

    val organizationId = OtlpAuth.extractOrgId(call, otlpApiKeyService)
    if (organizationId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid OTLP API key"))
        return
    }

    val bodyBytes = call.receive<ByteArray>()
    val encoding = call.request.header(HttpHeaders.ContentEncoding)
    val payloadBytes = suspendRunCatching {
        DecompressionService.decompress(bodyBytes, encoding)
    }.getOrElse { _ ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to decompress request body"))
        return
    }

    val parsedFeedback = if (isProtobuf) {
        feedbackService.parseOtlpFeedbackProtobuf(payloadBytes)
    } else {
        feedbackService.parseOtlpFeedbackJson(payloadBytes.decodeToString())
    }
    if (parsedFeedback == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid OTLP feedback payload"))
        return
    }
    if (parsedFeedback.isEmpty()) {
        call.respond(HttpStatusCode.Accepted, OtlpFeedbackIngestResult(accepted = 0, unmapped = 0))
        return
    }

    val routedFeedback = routeFeedback(organizationId, parsedFeedback, otlpServiceRoutingService)
    val mappedFeedback = routedFeedback.filter { it.projectId != null }
    val unmapped = routedFeedback.size - mappedFeedback.size
    if (mappedFeedback.isEmpty()) {
        call.respond(HttpStatusCode.Accepted, OtlpFeedbackIngestResult(accepted = 0, unmapped = unmapped))
        return
    }

    if (quotaService.isEnforcementEnabled()) {
        val reservation = quotaService.reserveUnits(
            organizationId = organizationId,
            requestedUnits = mappedFeedback.size,
            eventType = "feedback",
            requestedBytes = payloadBytes.size.toLong()
        )
        if (!reservation.allowed) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                QuotaExceededResponse(reason = reservation.reason, usage = reservation.usage)
            )
            return
        }
    }

    val result = feedbackService.insertFeedback(organizationId, mappedFeedback)
    call.respond(HttpStatusCode.Accepted, result.copy(unmapped = result.unmapped + unmapped))
}

private fun routeFeedback(
    organizationId: Int,
    rows: List<OtlpFeedbackInsert>,
    routingService: OtlpServiceRoutingService,
): List<OtlpFeedbackInsert> {
    val descriptors = rows.map { row ->
        OtlpServiceDescriptor(
            serviceNamespace = row.serviceNamespace,
            serviceName = row.service,
            environment = row.environment,
        )
    }
    val projectIds = routingService.resolveProjectIds(organizationId, descriptors, OtlpSignalType.FEEDBACK)
    return rows.map { row ->
        val identity = routingService.normalizeIdentity(row.serviceNamespace, row.service)
        row.copy(projectId = identity?.let { projectIds[it] })
    }
}
