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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DatadogLogEntry
import com.moneat.datadog.services.DatadogLogService
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
                    "Accepted $count DD logs for org $orgId"
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
    } catch (e: SerializationException) {
        logger.warn(e) { "Failed to parse DD log payload" }
        null
    } catch (e: IOException) {
        logger.warn(e) { "Failed to parse DD log payload" }
        null
    } catch (e: IllegalStateException) {
        logger.warn(e) { "Failed to parse DD log payload" }
        null
    } catch (e: IllegalArgumentException) {
        logger.warn(e) { "Failed to parse DD log payload" }
        null
    }
}
