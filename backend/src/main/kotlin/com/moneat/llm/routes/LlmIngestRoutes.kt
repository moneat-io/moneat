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

package com.moneat.llm.routes

import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.RedisConfig
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.events.routes.extractPublicKey
import com.moneat.events.services.EventService
import com.moneat.llm.models.LlmIngestPayload
import com.moneat.llm.services.LlmIngestionWorker
import com.moneat.utils.ErrorResponse
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.koin.core.context.GlobalContext

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

fun Route.llmIngestRoutes() {
    val eventService = GlobalContext.get().get<EventService>()
    val quotaService = GlobalContext.get().get<BillingQuotaService>()
    val projectIdResolver = GlobalContext.get().get<ProjectIdResolver>()

    route("/api/{projectId}") {
        post("/llm/") {
            val queueKey =
                call.application.environment.config
                    .propertyOrNull("llm.queueKey")
                    ?.getString()
                    ?: "moneat:llm:queue"
            val projectId = call.parameters["projectId"]?.let(projectIdResolver::resolveProtocolId)
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

            suspendRunCatching {
                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()
                val decompressedBytes = DecompressionService.decompress(bodyBytes, contentEncoding)

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
                    val reservation =
                        quotaService.reserveUnits(
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
            }.getOrElse { e ->
                logger.error(e) { "Failed to process LLM ingest payload: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid LLM payload"))
            }
        }
    }
}
