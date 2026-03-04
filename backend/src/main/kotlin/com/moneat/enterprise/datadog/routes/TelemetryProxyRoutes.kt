// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import com.moneat.enterprise.datadog.services.TelemetryProxyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.telemetryProxyRoutes() {
    route("/dd/telemetry") {
        // POST /dd/telemetry/proxy/* - agent telemetry proxy
        post("/proxy/{path...}") { handleTelemetryProxy() }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun io.ktor.server.routing.RoutingContext.handleTelemetryProxy() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val path = call.parameters.getAll("path")
            ?.joinToString("/") ?: "unknown"

        TelemetryProxyService.acknowledge(organizationId, path, rawBytes.size)
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process telemetry proxy request" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }
}
