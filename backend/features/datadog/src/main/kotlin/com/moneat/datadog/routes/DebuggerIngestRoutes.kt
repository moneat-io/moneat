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
import com.moneat.datadog.models.DdDebuggerDiagnostic
import com.moneat.datadog.models.DdDebuggerInput
import com.moneat.datadog.services.DebuggerIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

fun Route.debuggerIngestRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd/debugger/v1") {
        // POST /dd/debugger/v1/input - debugger probe data
        post("/input") { handleDebuggerInput(quotaService) }

        // POST /dd/debugger/v1/diagnostics - debugger diagnostics
        post("/diagnostics") { handleDebuggerDiagnostics(quotaService) }
    }

    route("/dd/debugger/v2") {
        // POST /dd/debugger/v2/input - v2 debugger probe data
        post("/input") { handleDebuggerInput(quotaService) }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDebuggerInput(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val entries = json.decodeFromString<List<DdDebuggerInput>>(body)
        if (!reserveDebuggerQuota(quotaService, organizationId, entries.size, bytes)) return
        val count = admitDebuggerWithQuotaRefund(quotaService, organizationId, entries.size, bytes) {
            DebuggerIngestionService.enqueueDebuggerLogs(organizationId, entries)
        }

        logger.debug { "Enqueued $count debugger entries for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        e.rethrowIfQueueAdmission()
        logger.error(e) { "Failed to process debugger input" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDebuggerDiagnostics(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val entries = json.decodeFromString<List<DdDebuggerDiagnostic>>(body)
        if (!reserveDebuggerQuota(quotaService, organizationId, entries.size, bytes)) return
        val count = admitDebuggerWithQuotaRefund(quotaService, organizationId, entries.size, bytes) {
            DebuggerIngestionService.enqueueDiagnostics(organizationId, entries)
        }

        logger.debug { "Enqueued $count debugger diagnostics for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        e.rethrowIfQueueAdmission()
        logger.error(e) { "Failed to process debugger diagnostics" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.reserveDebuggerQuota(
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
        eventType = "dd_debugger",
        requestedBytes = bytes.size.toLong(),
    )
}

private inline fun <T> admitDebuggerWithQuotaRefund(
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnits: Int,
    bytes: ByteArray,
    admit: () -> T,
): T =
    admitDatadogWithQuotaRefund(
        quotaService,
        DatadogQuotaCharge(organizationId, requestedUnits, "dd_debugger", bytes.size.toLong()),
        admit,
    )
