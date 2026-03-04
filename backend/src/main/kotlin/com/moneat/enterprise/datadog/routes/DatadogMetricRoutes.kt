// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import com.moneat.enterprise.datadog.decompression.DecompressionService
import com.moneat.enterprise.datadog.decompression.MetricPayloadDecoder
import com.moneat.enterprise.datadog.decompression.SketchPayloadDecoder
import com.moneat.enterprise.datadog.models.DatadogMetricSeriesV1
import com.moneat.enterprise.datadog.models.DatadogSketchPayload
import com.moneat.enterprise.datadog.services.DatadogHostService
import com.moneat.enterprise.datadog.services.DatadogMetricService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
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

                val payload = try {
                    json.decodeFromString<DatadogMetricSeriesV1>(
                        bodyStr
                    )
                } catch (e: Exception) {
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
                    "Accepted $count DD V1 metrics for org ${orgId}"
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

                val payload = try {
                    if (contentType.contains("application/json")) {
                        json.decodeFromString<DatadogMetricSeriesV1>(body.decodeToString())
                    } else {
                        MetricPayloadDecoder.decode(body)
                    }
                } catch (e: Exception) {
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

                logger.debug { "Accepted $count DD V3 metrics for org ${orgId}" }
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

                val payload = try {
                    if (contentType.contains("application/json")) {
                        json.decodeFromString<DatadogMetricSeriesV1>(body.decodeToString())
                    } else {
                        MetricPayloadDecoder.decode(body)
                    }
                } catch (e: Exception) {
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

                logger.debug { "Accepted $count DD V2 metrics for org ${orgId}" }
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

    val payload = try {
        if (contentType.contains("application/x-protobuf")) {
            SketchPayloadDecoder.decode(body)
        } else {
            val bodyStr = body.decodeToString()
            json.decodeFromString<DatadogSketchPayload>(bodyStr)
        }
    } catch (e: Exception) {
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
            "for org ${orgId}"
    }

    call.respond(
        HttpStatusCode.Accepted,
        mapOf("status" to "ok")
    )
}
