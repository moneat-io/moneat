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
import com.moneat.datadog.decompression.ProcessAgentPayloadDecoder
import com.moneat.datadog.services.DatadogInfraService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

fun Route.datadogInfraRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    // Process-agent sends to /api/v1/* without /dd/ prefix
    route("/api/v1") {
        post("/discovery") {
            DatadogAuthMiddleware.authenticate(call) ?: return@post
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
        post("/container") { handleContainer(call, quotaService) }
    }

    route("/dd") {
        route("/api/v1") {
            post("/collector") {
                val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@post
                handleProcessAgentPayload(call, quotaService, orgId)
            }

            post("/container") { handleContainer(call, quotaService) }

            post("/connections") {
                val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@post
                // CollectorConnections uses complex IP-byte encoding; acknowledge and skip
                call.receive<ByteArray>()
                logger.debug { "Accepted DD connections payload for org $orgId" }
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            }
        }
    }
}

private suspend fun handleContainer(
    call: ApplicationCall,
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    handleProcessAgentPayload(call, quotaService, orgId)
}

private suspend fun handleProcessAgentPayload(
    call: ApplicationCall,
    quotaService: BillingQuotaService,
    orgId: Int
) {
    val rawBody = call.receive<ByteArray>()
    val header = ProcessAgentPayloadDecoder.readHeader(rawBody)

    if (header == null) {
        logger.warn { "Received non-MessageV3 payload on DD infra endpoint for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
        return
    }

    val proto = suspendRunCatching {
        ProcessAgentPayloadDecoder.decompressBody(rawBody, header.encoding)
    }.getOrElse { e ->
        logger.warn(e) { "Failed to decompress DD infra payload (type=${header.type}) for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
        return
    }

    when (header.type) {
        ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONTAINER -> {
            val payload = suspendRunCatching {
                ProcessAgentPayloadDecoder.decodeCollectorContainer(proto)
            }.getOrElse { e ->
                logger.warn(e) { "Failed to decode CollectorContainer for org $orgId" }
                return call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            }
            val batch = DatadogInfraService.mapContainers(orgId.toLong(), payload)
            if (!reserveDatadogQuota(
                    call,
                    quotaService,
                    orgId,
                    batch.containers.size,
                    "dd_infra",
                    proto.size.toLong(),
                )
            ) {
                return
            }
            val count = DatadogInfraService.enqueueInfra(batch)
            logger.debug { "Accepted $count DD containers for org $orgId" }
        }
        ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC,
        ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC_DISCOVERY -> {
            val payload = suspendRunCatching {
                ProcessAgentPayloadDecoder.decodeCollectorProc(proto)
            }.getOrElse { e ->
                logger.warn(e) { "Failed to decode CollectorProc for org $orgId" }
                return call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            }
            val batch = DatadogInfraService.mapProcesses(orgId.toLong(), payload)
            if (!reserveDatadogQuota(
                    call,
                    quotaService,
                    orgId,
                    batch.processes.size,
                    "dd_infra",
                    proto.size.toLong(),
                )
            ) {
                return
            }
            val count = DatadogInfraService.enqueueInfra(batch)
            logger.debug { "Accepted $count DD processes for org $orgId" }
        }
        else -> {
            logger.debug { "Ignoring unhandled DD infra payload type=${header.type} for org $orgId" }
        }
    }
    call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
}
