// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import com.moneat.enterprise.datadog.decompression.DecompressionService
import com.moneat.enterprise.datadog.models.DatadogLogEntry
import com.moneat.enterprise.datadog.services.DatadogLogService
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

fun Route.datadogLogRoutes() {
    route("/dd") {
        route("/api/v2") {
            post("/logs") {
                val orgId = DatadogAuthMiddleware.authenticate(call)
                        ?: return@post

                val contentEncoding =
                    call.request.headers["Content-Encoding"]
                val rawBody = call.receive<ByteArray>()
                val body = DecompressionService.decompress(
                    rawBody,
                    contentEncoding
                )

                val bodyStr = body.decodeToString()
                val entries = parseLogEntries(bodyStr)

                if (entries == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid log payload"))
                    return@post
                }

                if (entries.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    return@post
                }

                val count = DatadogLogService.enqueueLogs(
                    organizationId = orgId.toLong(),
                    entries = entries
                )

                logger.debug {
                    "Accepted $count DD logs for org ${orgId}"
                }

                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
        }
    }
}

private fun parseLogEntries(bodyStr: String): List<DatadogLogEntry>? {
    return try {
        val trimmed = bodyStr.trimStart()
        if (trimmed.startsWith("[")) {
            json.decodeFromString<List<DatadogLogEntry>>(trimmed)
        } else {
            listOf(json.decodeFromString<DatadogLogEntry>(trimmed))
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to parse DD log payload" }
        null
    }
}
