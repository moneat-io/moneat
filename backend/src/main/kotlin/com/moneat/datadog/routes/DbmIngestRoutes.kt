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
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DdDbmActivityPayload
import com.moneat.datadog.models.DdDbmHealthPayload
import com.moneat.datadog.models.DdDbmMetadataPayload
import com.moneat.datadog.models.DdDbmMetricsPayload
import com.moneat.datadog.models.DdDbmQueryPayload
import com.moneat.datadog.services.DbmIngestionService
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
private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

fun Route.dbmIngestRoutes() {
    route("/dd/api/v2") {
        // POST /dd/api/v2/databasequery - query samples
        post("/databasequery") { handleDbmQueries() }

        // POST /dd/api/v2/dbmmetrics - query metrics
        post("/dbmmetrics") { handleDbmMetrics() }

        // POST /dd/api/v2/dbmactivity - active sessions
        post("/dbmactivity") { handleDbmActivity() }

        // POST /dd/api/v2/dbmmetadata - schema/explain metadata
        post("/dbmmetadata") { handleDbmMetadata() }

        // POST /dd/api/v2/dbmhealth - agent health checks
        post("/dbmhealth") { handleDbmHealth() }
    }

    // Event platform forwarder strips path from dd_url, so these arrive without /dd/ prefix.
    route("/api/v2") {
        post("/databasequery") { handleDbmQueries() }
        post("/dbmmetrics") { handleDbmMetrics() }
        post("/dbmactivity") { handleDbmActivity() }
        post("/dbmmetadata") { handleDbmMetadata() }
        post("/dbmhealth") { handleDbmHealth() }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmQueries() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdDbmQueryPayload>(body)
        val count = DbmIngestionService.enqueueQueries(organizationId, payload)

        logger.debug { "Enqueued $count DBM queries for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process DBM queries" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmMetrics() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdDbmMetricsPayload>(body)
        val count = DbmIngestionService.enqueueMetrics(organizationId, payload)

        logger.debug { "Enqueued $count DBM metrics for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process DBM metrics" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmActivity() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdDbmActivityPayload>(body)
        val count = DbmIngestionService.enqueueActivity(organizationId, payload)

        logger.debug { "Enqueued $count DBM activity for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process DBM activity" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmMetadata() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdDbmMetadataPayload>(body)
        val count = DbmIngestionService.enqueueMetadata(organizationId, payload)

        logger.debug { "Enqueued $count DBM metadata for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process DBM metadata" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmHealth() {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    try {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payload = json.decodeFromString<DdDbmHealthPayload>(body)
        val count = DbmIngestionService.enqueueHealth(organizationId, payload)

        logger.debug { "Enqueued $count DBM health for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    } catch (e: Exception) {
        logger.error(e) { "Failed to process DBM health" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
