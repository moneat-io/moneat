// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.security

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import com.moneat.enterprise.datadog.decompression.DecompressionService
import com.moneat.enterprise.datadog.models.DdActivityDumpPayload
import com.moneat.enterprise.datadog.models.DdCompliancePayload
import com.moneat.enterprise.datadog.models.DdSecurityEventPayload
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

@Suppress("TooGenericExceptionCaught")
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
    } catch (e: Exception) {
        logger.error(e) { "Failed to process security events" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

@Suppress("TooGenericExceptionCaught")
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
    } catch (e: Exception) {
        logger.error(e) { "Failed to process activity dumps" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

@Suppress("TooGenericExceptionCaught")
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
    } catch (e: Exception) {
        logger.error(e) { "Failed to process compliance findings" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
