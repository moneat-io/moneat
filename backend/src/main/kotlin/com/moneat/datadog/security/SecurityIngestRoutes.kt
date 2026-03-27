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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
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

private val logger = KotlinLogging.logger {}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun Route.securityIngestRoutes() {
    route("/dd/api/v2") {
        post("/security") { handleSecurityEvents() }
        post("/activity-dump") { handleActivityDumps() }
        post("/compliance") { handleComplianceFindings() }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleSecurityEvents() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    try {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdSecurityEventPayload>(body.decodeToString())
        val count = SecurityIngestionService.enqueueSecurityEvents(orgId, payload)
        logger.debug { "Accepted $count security events for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to process security events" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IOException) {
        logger.error(e) { "Failed to process security events" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IllegalStateException) {
        logger.error(e) { "Failed to process security events" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IllegalArgumentException) {
        logger.error(e) { "Failed to process security events" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleActivityDumps() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    try {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdActivityDumpPayload>(body.decodeToString())
        val count = SecurityIngestionService.enqueueActivityDumps(orgId, payload)
        logger.debug { "Accepted $count activity dumps for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to process activity dumps" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IOException) {
        logger.error(e) { "Failed to process activity dumps" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IllegalStateException) {
        logger.error(e) { "Failed to process activity dumps" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IllegalArgumentException) {
        logger.error(e) { "Failed to process activity dumps" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleComplianceFindings() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    try {
        val contentEncoding = call.request.headers["Content-Encoding"]
        val rawBody = call.receive<ByteArray>()
        val body = DecompressionService.decompress(rawBody, contentEncoding)
        val payload = json.decodeFromString<DdCompliancePayload>(body.decodeToString())
        val count = SecurityIngestionService.enqueueCompliance(orgId, payload)
        logger.debug { "Accepted $count compliance findings for org $orgId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to process compliance findings" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IOException) {
        logger.error(e) { "Failed to process compliance findings" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IllegalStateException) {
        logger.error(e) { "Failed to process compliance findings" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    } catch (e: IllegalArgumentException) {
        logger.error(e) { "Failed to process compliance findings" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
