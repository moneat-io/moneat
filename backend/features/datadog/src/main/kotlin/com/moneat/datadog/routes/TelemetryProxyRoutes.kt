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

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.services.TelemetryProxyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

fun Route.telemetryProxyRoutes() {
    route("/telemetry") {
        post("/proxy/{path...}") { handleTelemetryProxy() }
    }

    route("/dd/telemetry") {
        // POST /dd/telemetry/proxy/* - agent telemetry proxy
        post("/proxy/{path...}") { handleTelemetryProxy() }
    }

    route("/api/v2") {
        post("/apmtelemetry") { handleTelemetryProxy() }
    }

    route("/dd/api/v2") {
        post("/apmtelemetry") { handleTelemetryProxy() }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleTelemetryProxy() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val path = call.parameters.getAll("path")
            ?.joinToString("/")
            ?: call.request.path().trimStart('/')

        TelemetryProxyService.acknowledge(organizationId, path, rawBytes.size)
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process telemetry proxy request" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }
}
