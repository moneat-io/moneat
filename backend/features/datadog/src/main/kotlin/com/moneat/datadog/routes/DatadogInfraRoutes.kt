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
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.ProcessAgentPayloadDecoder
import com.moneat.datadog.services.DatadogInfraService
import com.moneat.utils.suspendRunCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val INVALID_CONNECTIONS_PAYLOAD_ERROR = "Invalid DD connections payload"

fun Route.datadogInfraRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    // Process-agent sends to /api/v1/* without /dd/ prefix
    route("/api/v1") {
        post("/discovery") {
            val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@post
            handleProcessAgentPayload(call, quotaService, orgId)
        }
        post("/collector") {
            val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@post
            handleProcessAgentPayload(call, quotaService, orgId)
        }
        post("/container") { handleContainer(call, quotaService) }
        post("/connections") { handleConnections(call, quotaService) }
    }

    route("/dd") {
        route("/api/v1") {
            post("/collector") {
                val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@post
                handleProcessAgentPayload(call, quotaService, orgId)
            }

            post("/container") { handleContainer(call, quotaService) }

            post("/connections") {
                handleConnections(call, quotaService)
            }
        }
    }
}

private suspend fun handleConnections(
    call: ApplicationCall,
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    val rawBody = call.receive<ByteArray>()
    val header = ProcessAgentPayloadDecoder.readHeader(rawBody)
    if (header == null) {
        logger.warn { "Received non-MessageV3 DD connections payload for org $orgId" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID_CONNECTIONS_PAYLOAD_ERROR))
        return
    }
    if (header.type != ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONNECTIONS) {
        logger.warn { "Received DD connections endpoint payload with type=${header.type} for org $orgId" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid DD connections payload type"))
        return
    }

    val proto = suspendRunCatching {
        ProcessAgentPayloadDecoder.decompressBody(rawBody, header.encoding)
    }.getOrElse { e ->
        logger.warn(e) { "Failed to decompress DD connections payload for org $orgId" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID_CONNECTIONS_PAYLOAD_ERROR))
        return
    }
    val payload = suspendRunCatching {
        ProcessAgentPayloadDecoder.decodeCollectorConnections(proto)
    }.getOrElse { e ->
        logger.warn(e) { "Failed to decode CollectorConnections for org $orgId" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to INVALID_CONNECTIONS_PAYLOAD_ERROR))
        return
    }
    val batch = DatadogInfraService.mapConnections(orgId.toLong(), payload)
    if (!reserveDatadogQuota(
            call,
            quotaService,
            orgId,
            batch.connections.size,
            "dd_infra",
            proto.size.toLong(),
        )
    ) {
        return
    }
    val count = admitDatadogWithQuotaRefund(
        quotaService,
        DatadogQuotaCharge(orgId, batch.connections.size, "dd_infra", proto.size.toLong()),
    ) {
        DatadogInfraService.enqueueInfra(batch)
    }
    logger.debug { "Accepted $count DD network connections for org $orgId" }
    respondProcessAgentCollectorOk(call)
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
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid DD infra payload"))
        return
    }

    val proto = suspendRunCatching {
        ProcessAgentPayloadDecoder.decompressBody(rawBody, header.encoding)
    }.getOrElse { e ->
        logger.warn(e) { "Failed to decompress DD infra payload (type=${header.type}) for org $orgId" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid DD infra payload"))
        return
    }

    when (header.type) {
        ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONTAINER -> {
            val payload = suspendRunCatching {
                ProcessAgentPayloadDecoder.decodeCollectorContainer(proto)
            }.getOrElse { e ->
                logger.warn(e) { "Failed to decode CollectorContainer for org $orgId" }
                return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid DD container payload"))
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
            val count = admitDatadogWithQuotaRefund(
                quotaService,
                DatadogQuotaCharge(orgId, batch.containers.size, "dd_infra", proto.size.toLong()),
            ) {
                DatadogInfraService.enqueueInfra(batch)
            }
            logger.debug { "Accepted $count DD containers for org $orgId" }
        }
        ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC,
        ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC_DISCOVERY -> {
            val payload = suspendRunCatching {
                if (header.type == ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC_DISCOVERY) {
                    ProcessAgentPayloadDecoder.decodeCollectorProcDiscovery(proto)
                } else {
                    ProcessAgentPayloadDecoder.decodeCollectorProc(proto)
                }
            }.getOrElse { e ->
                logger.warn(e) { "Failed to decode CollectorProc for org $orgId" }
                return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid DD process payload"))
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
            val count = admitDatadogWithQuotaRefund(
                quotaService,
                DatadogQuotaCharge(orgId, batch.processes.size, "dd_infra", proto.size.toLong()),
            ) {
                DatadogInfraService.enqueueInfra(batch)
            }
            logger.debug { "Accepted $count DD processes for org $orgId" }
        }
        else -> {
            logger.warn { "Received unsupported DD infra payload type=${header.type} for org $orgId" }
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported DD infra payload type"))
            return
        }
    }
    respondProcessAgentCollectorOk(call)
}

private suspend fun respondProcessAgentCollectorOk(call: ApplicationCall) {
    call.respondBytes(
        bytes = ProcessAgentPayloadDecoder.encodeCollectorResponse(),
        contentType = ContentType.Application.OctetStream,
        status = HttpStatusCode.Accepted,
    )
}
