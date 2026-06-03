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
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DatadogLogEntry
import com.moneat.datadog.services.DatadogLogService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun Route.datadogLogRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd") {
        route("/api/v2") {
            post("/logs") { handleDatadogLogs(quotaService) }
        }
    }

    route("/api/v2") {
        post("/logs") { handleDatadogLogs(quotaService) }
    }
}

private suspend fun RoutingContext.handleDatadogLogs(
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call)
        ?: return

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
        return
    }

    if (entries.isEmpty()) {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        return
    }

    if (!reserveDatadogQuota(call, quotaService, orgId, entries.size, "dd_log", body.size.toLong())) {
        return
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

private fun parseLogEntries(bodyStr: String): List<DatadogLogEntry>? {
    return suspendRunCatching {
        val trimmed = bodyStr.trimStart()
        if (trimmed.startsWith("[")) {
            json.decodeFromString<List<DatadogLogEntry>>(trimmed)
        } else {
            listOf(json.decodeFromString<DatadogLogEntry>(trimmed))
        }
    }.getOrElse { e ->
        logger.warn(e) { "Failed to parse DD log payload" }
        null
    }
}
