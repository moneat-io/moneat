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
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.ingest.DecompressionService
import com.moneat.datadog.models.DatadogEventPayload
import com.moneat.datadog.models.DatadogServiceCheck
import com.moneat.datadog.models.DatadogServiceCheckPayload
import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.QueuedServiceCheckBatch
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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

fun Route.datadogEventRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd") {
        route("/api/v1") {
            post("/check_run") {
                handleV1CheckRun(quotaService)
            }
        }

        route("/api/v2") {
            post("/events") {
                handleV2Events(quotaService)
            }

            post("/service_checks") {
                handleV2ServiceChecks(quotaService)
            }
        }
    }
}

private suspend fun RoutingContext.handleV1CheckRun(
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    val body = receiveDecompressedBody()
    val checks = decodeJsonPayload<List<DatadogServiceCheck>>(
        body,
        "Failed to parse DD V1 check_run payload"
    ) ?: return
    val batch = mapServiceChecks(orgId, checks)

    touchServiceCheckHosts(orgId, checks)
    if (!reserveEventQuota(quotaService, orgId, batch.serviceChecks.size, body)) return

    insertServiceCheckBatchIfPresent(batch)
    logger.debug {
        "Accepted ${batch.serviceChecks.size} DD V1 check_run service checks for org $orgId"
    }
    call.respondAccepted()
}

private suspend fun RoutingContext.handleV2Events(
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    val body = receiveDecompressedBody()
    val payload = decodeJsonPayload<DatadogEventPayload>(
        body,
        "Failed to parse DD events payload"
    ) ?: return

    if (!reserveEventQuota(quotaService, orgId, payload.events.size, body)) return

    val count = DatadogEventService.enqueueEvents(
        organizationId = orgId.toLong(),
        events = payload.events
    )
    logger.debug { "Accepted $count DD events for org $orgId" }
    call.respondAccepted()
}

private suspend fun RoutingContext.handleV2ServiceChecks(
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    val body = receiveDecompressedBody()
    val payload = decodeJsonPayload<DatadogServiceCheckPayload>(
        body,
        "Failed to parse DD service checks"
    ) ?: return
    val batch = mapServiceChecks(orgId, payload.serviceChecks)

    touchServiceCheckHosts(orgId, payload.serviceChecks)
    if (!reserveEventQuota(quotaService, orgId, batch.serviceChecks.size, body)) return

    insertServiceCheckBatchIfPresent(batch)
    logger.debug { "Accepted ${batch.serviceChecks.size} DD service checks for org $orgId" }
    call.respondAccepted()
}

private suspend fun RoutingContext.receiveDecompressedBody(): ByteArray {
    val contentEncoding = call.request.headers["Content-Encoding"]
    val rawBody = call.receive<ByteArray>()
    return DecompressionService.decompress(rawBody, contentEncoding)
}

private suspend inline fun <reified T> RoutingContext.decodeJsonPayload(
    body: ByteArray,
    failureMessage: String
): T? {
    return suspendRunCatching {
        json.decodeFromString<T>(body.decodeToString())
    }.getOrElse { e ->
        logger.warn(e) { failureMessage }
        call.respondInvalidPayload()
        null
    }
}

private fun mapServiceChecks(
    orgId: Int,
    checks: List<DatadogServiceCheck>
): QueuedServiceCheckBatch {
    return DatadogEventService.mapServiceChecks(
        organizationId = orgId.toLong(),
        checks = checks
    )
}

private suspend fun RoutingContext.reserveEventQuota(
    quotaService: BillingQuotaService,
    orgId: Int,
    requestedUnits: Int,
    body: ByteArray
): Boolean {
    return reserveDatadogQuota(
        call,
        quotaService,
        orgId,
        requestedUnits,
        "dd_event",
        body.size.toLong(),
    )
}

private suspend fun insertServiceCheckBatchIfPresent(batch: QueuedServiceCheckBatch) {
    if (batch.serviceChecks.isNotEmpty()) {
        DatadogEventService.insertServiceCheckBatch(batch)
    }
}

private fun touchServiceCheckHosts(orgId: Int, checks: List<DatadogServiceCheck>) {
    val hosts = checks
        .map { it.hostName }
        .filter { it.isNotBlank() }
        .toSet()
    DatadogHostService.touchHostLastSeen(orgId, hosts)
}

private suspend fun ApplicationCall.respondInvalidPayload() {
    respond(HttpStatusCode.BadRequest, mapOf("errors" to listOf("Invalid payload")))
}

private suspend fun ApplicationCall.respondAccepted() {
    respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
}
