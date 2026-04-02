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

package com.moneat.events.routes

import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.config.RedisConfig
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.services.EventService
import com.moneat.events.services.IngestionWorker
import com.moneat.logs.models.LogIngestEntry
import com.moneat.logs.services.LogService
import com.moneat.utils.DetailedErrorResponse
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import com.moneat.utils.suspendRunCatching
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val ENVELOPE_LOG_MAX_LENGTH = 500

private fun sha256HexPrefix(bytes: ByteArray, maxHexChars: Int): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { b -> "%02x".format(b) }.take(maxHexChars)
}

fun Route.ingestRoutes(
    eventService: EventService = GlobalContext.get().get(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
    logService: LogService = GlobalContext.get().get(),
    enqueueEnvelope: (queueKey: String, message: String) -> Unit = { queueKey, message ->
        RedisConfig.sync().lpush(queueKey, message)
    },
    isQuotaEnforcementEnabled: () -> Boolean = { quotaService.isEnforcementEnabled() },
    reserveEnvelopeQuota: (Int, Map<String, Int>, Map<String, Long>) -> QuotaReservationResult =
        { orgId, uByType, bByType -> quotaService.reserveUnitsBatch(orgId, uByType, bByType) },
    reserveLogQuota: (Int, Int, Long) -> QuotaReservationResult =
        { orgId, units, bytes -> quotaService.reserveUnits(orgId, units, "log", bytes) },
    reserveSingleQuota: (Int, Int, String, Long) -> QuotaReservationResult =
        { orgId, units, eType, bytes -> quotaService.reserveUnits(orgId, units, eType, bytes) }
) {
    route("/api/{projectId}") {
        // Sentry envelope endpoint (primary) - enqueue for async processing, respond 202
        post("/envelope/") {
            val queueKey =
                call.application.environment.config
                    .property("ingest.queueKey")
                    .getString()
            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }

            // Extract auth from header or query param
            val authHeader = call.request.header("X-Sentry-Auth")
            val sentryKey = call.request.queryParameters["sentry_key"]
            val publicKey = extractPublicKey(authHeader, sentryKey)

            if (publicKey == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing or invalid authentication")
                return@post
            }

            // Verify DSN
            val verification = eventService.verifyProjectKey(projectId, publicKey)
            if (!verification.isValid) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }

            // Parse envelope - handle gzip compression (validates payload before enqueue)
            suspendRunCatching {
                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()

                val decompressedBytes = DecompressionService.decompress(bodyBytes, contentEncoding)

                logger.debug { "Received envelope for project $projectId" }
                logger.debug {
                    "Envelope payload (redacted): bytes=${decompressedBytes.size}, " +
                        "sha256_prefix=${sha256HexPrefix(decompressedBytes, ENVELOPE_LOG_MAX_LENGTH)}"
                }

                val envelope = SentryEnvelope.parse(decompressedBytes)
                logger.debug { "Envelope parsed successfully, items: ${envelope.items.size}" }

                if (isQuotaEnforcementEnabled()) {
                    val orgId = eventService.getOrganizationIdForProject(projectId)
                    if (orgId == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
                        return@post
                    }
                    val groupedReservations =
                        envelope.items
                            .groupingBy { mapEnvelopeItemTypeToQuotaType(it.type) }
                            .eachCount()

                    // Calculate bytes per type for GB quota tracking
                    val groupedBytes =
                        envelope.items
                            .groupBy { mapEnvelopeItemTypeToQuotaType(it.type) }
                            .mapValues { (_, items) ->
                                items.sumOf { item ->
                                    (item.payloadBytes?.size ?: item.payload.toByteArray(Charsets.UTF_8).size).toLong()
                                }
                            }

                    val reservation = reserveEnvelopeQuota(orgId, groupedReservations, groupedBytes)
                    if (!reservation.allowed) {
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            ErrorResponse("Quota exceeded: ${reservation.reason}")
                        )
                        return@post
                    }
                }

                val message = IngestionWorker.encodeMessage(projectId, decompressedBytes)
                enqueueEnvelope(queueKey, message)

                call.respond(HttpStatusCode.Accepted, mapOf("id" to envelope.eventId))
            }.getOrElse { e ->
                logger.error(e) { "Failed to process envelope: ${e.message}" }
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.BadRequest,
                    DetailedErrorResponse("Invalid envelope format", e.message ?: "Unknown error")
                )
            }
        }

        // Structured logs endpoint
        post("/logs/") {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }

            val authHeader = call.request.header("X-Sentry-Auth")
            val sentryKey = call.request.queryParameters["sentry_key"]
            val publicKey =
                extractPublicKey(authHeader, sentryKey)
                    ?: extractPublicKeyFromDsn(call.request.header(HttpHeaders.Authorization))
                    ?: extractPublicKeyFromDsn(call.request.header("x-moneat-dsn"))

            if (publicKey == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }

            val verification = eventService.verifyProjectKey(projectId, publicKey)
            if (!verification.isValid) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }

            val organizationId = eventService.getOrganizationIdForProject(projectId)
            if (organizationId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
                return@post
            }

            val contentEncoding = call.request.header(HttpHeaders.ContentEncoding)
            val bodyBytes = call.receive<ByteArray>()
            val payloadBytes =
                if (contentEncoding == "gzip") {
                    java.util.zip
                        .GZIPInputStream(bodyBytes.inputStream())
                        .readBytes()
                } else {
                    bodyBytes
                }

            val entries =
                suspendRunCatching {
                    json.decodeFromString<List<LogIngestEntry>>(payloadBytes.decodeToString())
                }.getOrElse { e ->
                    logger.warn(e) { "Invalid log payload for project $projectId" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid log payload"))
                    return@post
                }

            if (entries.isEmpty()) {
                call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
                return@post
            }

            if (isQuotaEnforcementEnabled()) {
                val billableBytes = logService.estimateBillableBytes(entries)
                val reservation = reserveLogQuota(organizationId, entries.size, billableBytes)
                if (!reservation.allowed) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse("Quota exceeded: ${reservation.reason}")
                    )
                    return@post
                }
            }

            val queueKey =
                call.application.environment.config
                    .propertyOrNull("logs.queueKey")
                    ?.getString()
                    ?: "moneat:logs:queue"
            val accepted = logService.enqueueSdkLogs(organizationId.toLong(), entries, queueKey)
            call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
        }

        // Legacy store endpoint
        post("/store/") {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }

            val authHeader = call.request.header("X-Sentry-Auth")
            val sentryKey = call.request.queryParameters["sentry_key"]
            val publicKey = extractPublicKey(authHeader, sentryKey)

            if (publicKey == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }
            val verification = eventService.verifyProjectKey(projectId, publicKey)
            if (!verification.isValid) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }

            val body = call.receiveText()
            logger.debug { "Received store event for project $projectId" }

            suspendRunCatching {
                if (isQuotaEnforcementEnabled()) {
                    val orgId = eventService.getOrganizationIdForProject(projectId)
                    if (orgId == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
                        return@post
                    }
                    val bodyBytes = body.toByteArray(Charsets.UTF_8).size.toLong()
                    val reservation = reserveSingleQuota(orgId, 1, "error", bodyBytes)
                    if (!reservation.allowed) {
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            ErrorResponse("Quota exceeded: ${reservation.reason}")
                        )
                        return@post
                    }
                }

                eventService.processStoreEvent(projectId, body)
                call.respond(HttpStatusCode.OK)
            }.getOrElse { e ->
                logger.error(e) { "Failed to process store event" }
                call.respond(HttpStatusCode.BadRequest, "Invalid event format")
            }
        }

        // Security/CORS preflight
        get("/security/") {
            call.respond(HttpStatusCode.OK)
        }
    }
}

fun extractPublicKey(
    authHeader: String?,
    sentryKeyParam: String? = null
): String? {
    val headerKey =
        authHeader?.let { header ->
            // Parse "Sentry sentry_key=xxx, sentry_version=7"
            val keyRegex = "(?i)sentry_key=([a-z0-9_-]+)".toRegex()
            keyRegex.find(header)?.groupValues?.get(1)
        }
    if (headerKey != null) return headerKey

    // Fallback for SDKs that pass auth in query params:
    // ?sentry_key=xxx&sentry_version=7&sentry_client=...
    val keyRegex = "^[a-zA-Z0-9_-]+$".toRegex()
    return sentryKeyParam?.takeIf { keyRegex.matches(it) }
}

fun extractPublicKeyFromDsn(dsnLikeHeader: String?): String? {
    if (dsnLikeHeader.isNullOrBlank()) return null
    val cleaned = dsnLikeHeader.removePrefix("DSN ").trim()
    val regex = "https?://([a-zA-Z0-9_-]+)@[^/]+/[0-9]+".toRegex(RegexOption.IGNORE_CASE)
    return regex.find(cleaned)?.groupValues?.getOrNull(1)
}

internal fun mapEnvelopeItemTypeToQuotaType(itemType: String): String {
    return when (itemType) {
        "transaction" -> "transaction"
        "replay_event", "replay_recording", "replay_video" -> "replay"
        "feedback" -> "feedback"
        "llm_generation" -> "llm"
        else -> "error"
    }
}
