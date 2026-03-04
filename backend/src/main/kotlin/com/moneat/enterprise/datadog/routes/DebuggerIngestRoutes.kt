// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import com.moneat.enterprise.datadog.decompression.DecompressionService
import com.moneat.enterprise.datadog.models.DdDebuggerDiagnostic
import com.moneat.enterprise.datadog.models.DdDebuggerInput
import com.moneat.enterprise.datadog.services.DebuggerIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun Route.debuggerIngestRoutes() {
    route("/dd/debugger/v1") {
        // POST /dd/debugger/v1/input - debugger probe data
        post("/input") { handleDebuggerInput() }

        // POST /dd/debugger/v1/diagnostics - debugger diagnostics
        post("/diagnostics") { handleDebuggerDiagnostics() }
    }

    route("/dd/debugger/v2") {
        // POST /dd/debugger/v2/input - v2 debugger probe data
        post("/input") { handleDebuggerInput() }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun io.ktor.server.routing.RoutingContext.handleDebuggerInput() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val entries = json.decodeFromString<List<DdDebuggerInput>>(body)
        val count = DebuggerIngestionService.enqueueDebuggerLogs(organizationId, entries)

        logger.debug { "Enqueued $count debugger entries for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process debugger input" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun io.ktor.server.routing.RoutingContext.handleDebuggerDiagnostics() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val entries = json.decodeFromString<List<DdDebuggerDiagnostic>>(body)
        val count = DebuggerIngestionService.enqueueDiagnostics(organizationId, entries)

        logger.debug { "Enqueued $count debugger diagnostics for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process debugger diagnostics" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}


