// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import com.moneat.enterprise.datadog.decompression.DecompressionService
import com.moneat.enterprise.datadog.models.DatadogEventPayload
import com.moneat.enterprise.datadog.models.DatadogServiceCheck
import com.moneat.enterprise.datadog.models.DatadogServiceCheckPayload
import com.moneat.enterprise.datadog.services.DatadogEventService
import com.moneat.enterprise.datadog.services.DatadogHostService
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

fun Route.datadogEventRoutes() {
    route("/dd") {
        route("/api/v1") {
            post("/check_run") {
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

                val checks = try {
                    json.decodeFromString<List<DatadogServiceCheck>>(
                        bodyStr
                    )
                } catch (e: Exception) {
                    logger.warn(e) {
                        "Failed to parse DD V1 check_run payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                }

                val batch =
                    DatadogEventService.mapServiceChecks(
                        organizationId =
                            orgId.toLong(),
                        checks = checks
                    )

                if (batch.serviceChecks.isNotEmpty()) {
                    DatadogEventService
                        .insertServiceCheckBatch(batch)
                }

                val hosts = checks
                    .map { it.hostName }
                    .filter { it.isNotBlank() }
                    .toSet()
                DatadogHostService.touchHostLastSeen(orgId, hosts)

                logger.debug {
                    "Accepted ${batch.serviceChecks.size} " +
                        "DD V1 check_run service checks " +
                        "for org ${orgId}"
                }

                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("status" to "ok")
                )
            }
        }

        route("/api/v2") {
            post("/events") {
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
                    json.decodeFromString<DatadogEventPayload>(
                        bodyStr
                    )
                } catch (e: Exception) {
                    logger.warn(e) {
                        "Failed to parse DD events payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                }

                val count = DatadogEventService.enqueueEvents(
                    organizationId =
                        orgId.toLong(),
                    events = payload.events
                )

                logger.debug {
                    "Accepted $count DD events for " +
                        "org ${orgId}"
                }

                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("status" to "ok")
                )
            }

            post("/service_checks") {
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
                    json.decodeFromString<DatadogServiceCheckPayload>(
                        bodyStr
                    )
                } catch (e: Exception) {
                    logger.warn(e) {
                        "Failed to parse DD service checks"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                }

                val batch =
                    DatadogEventService.mapServiceChecks(
                        organizationId =
                            orgId.toLong(),
                        checks = payload.serviceChecks
                    )

                if (batch.serviceChecks.isNotEmpty()) {
                    DatadogEventService
                        .insertServiceCheckBatch(batch)
                }

                val hosts = payload.serviceChecks
                    .map { it.hostName }
                    .filter { it.isNotBlank() }
                    .toSet()
                DatadogHostService.touchHostLastSeen(orgId, hosts)

                logger.debug {
                    "Accepted ${batch.serviceChecks.size} " +
                        "DD service checks for " +
                        "org ${orgId}"
                }

                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("status" to "ok")
                )
            }
        }
    }
}
