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
import com.moneat.datadog.models.DatadogEventPayload
import com.moneat.datadog.models.DatadogServiceCheck
import com.moneat.datadog.models.DatadogServiceCheckPayload
import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.DatadogHostService
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
                } catch (e: SerializationException) {
                    logger.warn(e) {
                        "Failed to parse DD V1 check_run payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IOException) {
                    logger.warn(e) {
                        "Failed to parse DD V1 check_run payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IllegalStateException) {
                    logger.warn(e) {
                        "Failed to parse DD V1 check_run payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IllegalArgumentException) {
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
                        "for org $orgId"
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
                } catch (e: SerializationException) {
                    logger.warn(e) {
                        "Failed to parse DD events payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IOException) {
                    logger.warn(e) {
                        "Failed to parse DD events payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IllegalStateException) {
                    logger.warn(e) {
                        "Failed to parse DD events payload"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IllegalArgumentException) {
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
                        "org $orgId"
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
                } catch (e: SerializationException) {
                    logger.warn(e) {
                        "Failed to parse DD service checks"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IOException) {
                    logger.warn(e) {
                        "Failed to parse DD service checks"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IllegalStateException) {
                    logger.warn(e) {
                        "Failed to parse DD service checks"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                } catch (e: IllegalArgumentException) {
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
                        "org $orgId"
                }

                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("status" to "ok")
                )
            }
        }
    }
}
