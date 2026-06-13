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
import com.moneat.ingest.DecompressionService
import com.moneat.datadog.models.DdDbmActivityPayload
import com.moneat.datadog.models.DdDbmHealthPayload
import com.moneat.datadog.models.DdDbmMetadataPayload
import com.moneat.datadog.models.DdDbmMetricsPayload
import com.moneat.datadog.models.DdDbmQueryPayload
import com.moneat.datadog.services.DbmIngestionService
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

fun Route.dbmIngestRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd/api/v2") {
        // POST /dd/api/v2/databasequery - query samples
        post("/databasequery") { handleDbmQueries(quotaService) }

        // POST /dd/api/v2/dbmmetrics - query metrics
        post("/dbmmetrics") { handleDbmMetrics(quotaService) }

        // POST /dd/api/v2/dbmactivity - active sessions
        post("/dbmactivity") { handleDbmActivity(quotaService) }

        // POST /dd/api/v2/dbmmetadata - schema/explain metadata
        post("/dbmmetadata") { handleDbmMetadata(quotaService) }

        // POST /dd/api/v2/dbmhealth - agent health checks
        post("/dbmhealth") { handleDbmHealth(quotaService) }
    }

    // Event platform forwarder strips path from dd_url, so these arrive without /dd/ prefix.
    route("/api/v2") {
        post("/databasequery") { handleDbmQueries(quotaService) }
        post("/dbmmetrics") { handleDbmMetrics(quotaService) }
        post("/dbmactivity") { handleDbmActivity(quotaService) }
        post("/dbmmetadata") { handleDbmMetadata(quotaService) }
        post("/dbmhealth") { handleDbmHealth(quotaService) }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmQueries(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payloads = decodeDbmPayloads<DdDbmQueryPayload>(body)
        if (!reserveDbmQuota(quotaService, organizationId, payloads.sumOf { it.rows.size }, bytes)) return
        val count = DbmIngestionService.enqueueQueryPayloads(organizationId, payloads)

        logger.debug { "Enqueued $count DBM queries for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process DBM queries" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmMetrics(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payloads = decodeDbmPayloads<DdDbmMetricsPayload>(body)
        if (!reserveDbmQuota(quotaService, organizationId, payloads.sumOf { it.rows.size }, bytes)) return
        val count = DbmIngestionService.enqueueMetricPayloads(organizationId, payloads)

        logger.debug { "Enqueued $count DBM metrics for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process DBM metrics" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmActivity(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payloads = decodeDbmPayloads<DdDbmActivityPayload>(body)
        if (!reserveDbmQuota(quotaService, organizationId, payloads.sumOf { it.activity.size }, bytes)) return
        val count = DbmIngestionService.enqueueActivityPayloads(organizationId, payloads)

        logger.debug { "Enqueued $count DBM activity for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process DBM activity" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmMetadata(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payloads = decodeDbmPayloads<DdDbmMetadataPayload>(body)
        if (!reserveDbmQuota(quotaService, organizationId, payloads.size, bytes)) return
        val count = DbmIngestionService.enqueueMetadataPayloads(organizationId, payloads)

        logger.debug { "Enqueued $count DBM metadata for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process DBM metadata" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDbmHealth(
    quotaService: BillingQuotaService,
) {
    val organizationId = DatadogAuthMiddleware.authenticate(call) ?: return

    suspendRunCatching {
        val rawBytes = call.receiveChannel().toByteArray()
        val bytes = DecompressionService.decompress(rawBytes, call.request.headers["Content-Encoding"])
        val body = bytes.decodeToString()

        val payloads = decodeDbmPayloads<DdDbmHealthPayload>(body)
        if (!reserveDbmQuota(quotaService, organizationId, payloads.size, bytes)) return
        val count = DbmIngestionService.enqueueHealthPayloads(organizationId, payloads)

        logger.debug { "Enqueued $count DBM health for org=$organizationId" }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
    }.getOrElse { e ->
        logger.error(e) { "Failed to process DBM health" }
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.reserveDbmQuota(
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnits: Int,
    bytes: ByteArray,
): Boolean {
    return reserveDatadogQuota(
        call = call,
        quotaService = quotaService,
        organizationId = organizationId,
        requestedUnits = requestedUnits,
        eventType = "dd_dbm",
        requestedBytes = bytes.size.toLong(),
    )
}

private inline fun <reified T> decodeDbmPayloads(body: String): List<T> {
    return when (val root = json.parseToJsonElement(body)) {
        is JsonArray -> root.mapNotNull { element ->
            val objectElement = element as? JsonObject
            if (objectElement?.isEmpty() == true) {
                null
            } else {
                json.decodeFromJsonElement<T>(element)
            }
        }
        is JsonObject -> listOf(json.decodeFromJsonElement(root))
        else -> throw IllegalArgumentException("DBM payload must be a JSON object or array")
    }
}
