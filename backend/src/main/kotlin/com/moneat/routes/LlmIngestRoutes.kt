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

package com.moneat.routes

import com.moneat.config.RedisConfig
import com.moneat.models.LlmIngestPayload
import com.moneat.services.BillingQuotaService
import com.moneat.services.EmailService
import com.moneat.services.EventService
import com.moneat.services.LlmIngestionWorker
import com.moneat.services.NotificationService
import com.moneat.utils.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

fun Route.llmIngestRoutes() {
    val emailService = EmailService()
    val notificationService = NotificationService(emailService)
    val eventService = EventService(notificationService)
    val quotaService = BillingQuotaService()

    route("/api/{projectId}") {
        post("/llm/") {
            val queueKey = call.application.environment.config.propertyOrNull("llm.queueKey")?.getString()
                ?: "moneat:llm:queue"
            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }

            val authHeader = call.request.header("X-Sentry-Auth")
            val sentryKey = call.request.queryParameters["sentry_key"]
            val publicKey = extractPublicKey(authHeader, sentryKey)

            if (publicKey == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid authentication")
                return@post
            }

            val verification = eventService.verifyProjectKey(projectId, publicKey)
            if (!verification.isValid) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }

            try {
                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()
                val decompressedBytes = if (contentEncoding == "gzip") {
                    java.util.zip.GZIPInputStream(bodyBytes.inputStream()).readBytes()
                } else {
                    bodyBytes
                }

                val payload = json.decodeFromString<LlmIngestPayload>(decompressedBytes.decodeToString())

                if (payload.generations.isEmpty()) {
                    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
                    return@post
                }

                if (quotaService.isEnforcementEnabled()) {
                    val orgId = eventService.getOrganizationIdForProject(projectId)
                    if (orgId == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
                        return@post
                    }
                    val billableBytes = decompressedBytes.size.toLong()
                    val reservation = quotaService.reserveUnits(
                        organizationId = orgId,
                        requestedUnits = payload.generations.size,
                        eventType = "llm",
                        requestedBytes = billableBytes
                    )
                    if (!reservation.allowed) {
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            ErrorResponse("Quota exceeded: ${reservation.reason}")
                        )
                        return@post
                    }
                }

                val message = LlmIngestionWorker.encodeMessage(projectId, decompressedBytes)
                RedisConfig.sync().lpush(queueKey, message)

                call.respond(HttpStatusCode.Accepted, mapOf("accepted" to payload.generations.size))
            } catch (e: Exception) {
                logger.error(e) { "Failed to process LLM ingest payload: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid LLM payload"))
            }
        }
    }
}
