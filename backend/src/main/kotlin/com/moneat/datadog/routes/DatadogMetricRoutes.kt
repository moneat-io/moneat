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
import com.moneat.datadog.decompression.MetricPayloadDecoder
import com.moneat.datadog.decompression.SketchPayloadDecoder
import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogSketchPayload
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DatadogMetricService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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

fun Route.datadogMetricRoutes() {
    route("/dd") {
        route("/api/v1") {
            post("/series") {
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

                val payload = suspendRunCatching {
                    json.decodeFromString<DatadogMetricSeriesV1>(
                        bodyStr
                    )
                }.getOrElse { e ->
                    logger.warn(e) {
                        "Failed to parse DD V1 series payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf(
                                "Invalid payload"
                            )
                        )
                    )
                }

                val count = DatadogMetricService.enqueueMetrics(
                    organizationId = orgId.toLong(),
                    payload = payload
                )

                val hosts = payload.series
                    .map { it.host }
                    .filter { it.isNotBlank() }
                    .toSet()
                DatadogHostService.touchHostLastSeen(orgId, hosts)

                logger.debug {
                    "Accepted $count DD V1 metrics for org $orgId"
                }

                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("status" to "ok")
                )
            }

            post("/sketches") {
                handleSketches()
            }
        }

        route("/api/beta") {
            post("/sketches") {
                handleSketches()
            }
        }

        route("/api/v3") {
            post("/series") {
                val orgId = DatadogAuthMiddleware.authenticate(call)
                    ?: return@post

                val contentEncoding = call.request.headers["Content-Encoding"]
                val contentType = call.request.contentType().toString()
                val rawBody = call.receive<ByteArray>()
                val body = DecompressionService.decompress(rawBody, contentEncoding)

                val payload = suspendRunCatching {
                    if (contentType.contains("application/json")) {
                        json.decodeFromString<DatadogMetricSeriesV1>(body.decodeToString())
                    } else {
                        MetricPayloadDecoder.decode(body)
                    }
                }.getOrElse { e ->
                    logger.warn(e) { "Failed to parse DD V3 series payload" }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("errors" to listOf("Invalid payload"))
                    )
                }

                val count = DatadogMetricService.enqueueMetrics(
                    organizationId = orgId.toLong(),
                    payload = payload
                )

                val hosts = payload.series
                    .map { it.host }
                    .filter { it.isNotBlank() }
                    .toSet()
                DatadogHostService.touchHostLastSeen(orgId, hosts)

                logger.debug { "Accepted $count DD V3 metrics for org $orgId" }
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            }

            post("/sketches") {
                handleSketches()
            }
        }

        route("/api/v2") {
            post("/series") {
                val orgId = DatadogAuthMiddleware.authenticate(call)
                    ?: return@post

                val contentEncoding = call.request.headers["Content-Encoding"]
                val contentType = call.request.contentType().toString()
                val rawBody = call.receive<ByteArray>()
                val body = DecompressionService.decompress(rawBody, contentEncoding)

                val payload = suspendRunCatching {
                    if (contentType.contains("application/json")) {
                        json.decodeFromString<DatadogMetricSeriesV1>(body.decodeToString())
                    } else {
                        MetricPayloadDecoder.decode(body)
                    }
                }.getOrElse { e ->
                    logger.warn(e) { "Failed to parse DD V2 series payload" }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("errors" to listOf("Invalid payload"))
                    )
                }

                val count = DatadogMetricService.enqueueMetrics(
                    organizationId = orgId.toLong(),
                    payload = payload
                )

                val hosts = payload.series
                    .map { it.host }
                    .filter { it.isNotBlank() }
                    .toSet()
                DatadogHostService.touchHostLastSeen(orgId, hosts)

                logger.debug { "Accepted $count DD V2 metrics for org $orgId" }
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleSketches() {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return

    val contentEncoding = call.request.headers["Content-Encoding"]
    val contentType = call.request.contentType().toString()
    val rawBody = call.receive<ByteArray>()
    val body = DecompressionService.decompress(rawBody, contentEncoding)

    if (body.isEmpty()) {
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
        return
    }

    val payload = suspendRunCatching {
        if (contentType.contains("application/x-protobuf")) {
            SketchPayloadDecoder.decode(body)
        } else {
            val bodyStr = body.decodeToString()
            json.decodeFromString<DatadogSketchPayload>(bodyStr)
        }
    }.getOrElse { e ->
        logger.warn(e) { "Failed to parse DD sketches" }
        call.respond(
            HttpStatusCode.BadRequest,
            mapOf("errors" to listOf("Invalid payload"))
        )
        return
    }

    val batch = DatadogMetricService.mapSketches(
        organizationId = orgId.toLong(),
        payload = payload
    )

    if (batch.sketches.isNotEmpty()) {
        DatadogMetricService.insertSketchBatch(batch)
    }

    logger.debug {
        "Accepted ${batch.sketches.size} DD sketches " +
            "for org $orgId"
    }

    call.respond(
        HttpStatusCode.Accepted,
        mapOf("status" to "ok")
    )
}
