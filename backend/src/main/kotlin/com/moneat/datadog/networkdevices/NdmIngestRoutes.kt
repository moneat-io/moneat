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

package com.moneat.datadog.networkdevices

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DdNdmPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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

fun Route.ndmIngestRoutes() {
    route("/dd/api/v1") {
        post("/ndm") { handleNdmPayload() }
    }

    // Event platform forwarder strips path from dd_url, so these arrive without /dd/ prefix.
    route("/api/v2") {
        post("/ndm") { handleNdmPayload() }
        post("/ndmconfig") { handleNdmPayload() }
        post("/ndmtraps") { handleNdmPayload() }
        post("/ndmflow") { handleNdmPayload() }
        post("/netpath") { handleNdmPayload() }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleNdmPayload() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    try {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdNdmPayload>(body.decodeToString())

        val count = NdmIngestionService.enqueue(orgId, payload)
        logger.debug { "Accepted $count NDM entries (type=${payload.type}) for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process NDM payload" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
