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

package com.moneat.datadog.security

import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.reserveDatadogQuota
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.ingest.DecompressionService
import com.moneat.datadog.models.DdActivityDumpPayload
import com.moneat.datadog.models.DdCompliancePayload
import com.moneat.datadog.models.DdSecurityEventPayload
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

fun Route.securityIngestRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd/api/v2") {
        post("/security") { handleSecurityEvents(quotaService) }
        post("/activity-dump") { handleActivityDumps(quotaService) }
        post("/compliance") { handleComplianceFindings(quotaService) }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleSecurityEvents(
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdSecurityEventPayload>(body.decodeToString())
        if (!reserveSecurityQuota(quotaService, orgId, payload.events.size, body)) return
        val count = SecurityIngestionService.enqueueSecurityEvents(orgId, payload)
        logger.debug { "Accepted $count security events for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process security events" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleActivityDumps(
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdActivityDumpPayload>(body.decodeToString())
        if (!reserveSecurityQuota(quotaService, orgId, payload.dumps.size, body)) return
        val count = SecurityIngestionService.enqueueActivityDumps(orgId, payload)
        logger.debug { "Accepted $count activity dumps for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process activity dumps" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleComplianceFindings(
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    suspendRunCatching {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdCompliancePayload>(body.decodeToString())
        if (!reserveSecurityQuota(quotaService, orgId, payload.findings.size, body)) return
        val count = SecurityIngestionService.enqueueCompliance(orgId, payload)
        logger.debug { "Accepted $count compliance findings for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process compliance findings" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.reserveSecurityQuota(
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
        eventType = "dd_security",
        requestedBytes = bytes.size.toLong(),
    )
}
