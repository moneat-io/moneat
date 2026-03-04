// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import com.moneat.enterprise.datadog.models.DdManifestPayload
import com.moneat.enterprise.datadog.models.DdOrchestratorPayload
import com.moneat.enterprise.datadog.services.OrchestratorIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import com.moneat.enterprise.datadog.decompression.DecompressionService

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun Route.orchestratorIngestRoutes() {
    route("/dd/api/v2") {
        // POST /dd/api/v2/orch - K8s resource payloads
        post("/orch") { handleOrchestratorResources() }

        // POST /dd/api/v2/orchmanif - K8s manifest payloads
        post("/orchmanif") { handleOrchestratorManifests() }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun io.ktor.server.routing.RoutingContext.handleOrchestratorResources() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = decompressIfNeeded(rawBytes, call.request)
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdOrchestratorPayload>(body)
        val count = OrchestratorIngestionService.enqueueResources(organizationId, payload)

        logger.debug { "Enqueued $count K8s resources for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process orchestrator resources" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun io.ktor.server.routing.RoutingContext.handleOrchestratorManifests() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = decompressIfNeeded(rawBytes, call.request)
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdManifestPayload>(body)
        val count = OrchestratorIngestionService.enqueueManifests(organizationId, payload)

        logger.debug { "Enqueued $count K8s manifests for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
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
