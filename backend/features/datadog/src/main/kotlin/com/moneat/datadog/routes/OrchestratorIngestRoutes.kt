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
import com.moneat.datadog.models.DdManifestPayload
import com.moneat.datadog.models.DdOrchestratorPayload
import com.moneat.datadog.services.OrchestratorIngestionService
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

fun Route.orchestratorIngestRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd/api/v2") {
        // POST /dd/api/v2/orch - K8s resource payloads
        post("/orch") { handleOrchestratorResources(quotaService) }

        // POST /dd/api/v2/orchmanif - K8s manifest payloads
        post("/orchmanif") { handleOrchestratorManifests(quotaService) }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleOrchestratorResources(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = decompressIfNeeded(rawBytes, call.request)
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdOrchestratorPayload>(body)
        if (!reserveDatadogQuota(
                call = call,
                quotaService = quotaService,
                organizationId = organizationId,
                requestedUnits = payload.resources.size,
                eventType = "dd_orchestrator",
                requestedBytes = bytes.size.toLong(),
            )
        ) {
            return
        }
        val count = OrchestratorIngestionService.enqueueResources(organizationId, payload)

        logger.debug { "Enqueued $count K8s resources for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process orchestrator resources" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleOrchestratorManifests(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = decompressIfNeeded(rawBytes, call.request)
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdManifestPayload>(body)
        if (!reserveDatadogQuota(
                call = call,
                quotaService = quotaService,
                organizationId = organizationId,
                requestedUnits = payload.manifests.size,
                eventType = "dd_orchestrator",
                requestedBytes = bytes.size.toLong(),
            )
        ) {
            return
        }
        val count = OrchestratorIngestionService.enqueueManifests(organizationId, payload)

        logger.debug { "Enqueued $count K8s manifests for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process orchestrator manifests" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

private fun decompressIfNeeded(
    bytes: ByteArray,
    request: io.ktor.server.request.ApplicationRequest,
): ByteArray {
    val encoding = request.headers["Content-Encoding"]
    return DecompressionService.decompress(bytes, encoding)
}
