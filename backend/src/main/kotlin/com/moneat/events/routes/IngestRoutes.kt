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
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.events.models.EnvelopeItem
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.models.isFeedbackEventPayload
import com.moneat.events.services.EventService
import com.moneat.events.services.IngestionWorker
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.logs.models.LogIngestEntry
import com.moneat.logs.services.LogService
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.utils.DetailedErrorResponse
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val SHA256_HEX_PREFIX_CHARS = 16
private const val PROJECT_ID_DSN_SEGMENT_PATTERN =
    "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9]+)"

class IngestRouteDependencies {
    var eventService: EventService = GlobalContext.get().get()
    var quotaService: BillingQuotaService = GlobalContext.get().get()
    var logService: LogService = GlobalContext.get().get()
    var projectIdResolver: ProjectIdResolver = ProjectIdResolver()
    var enqueueEnvelope: (queueKey: String, message: String) -> Unit = { queueKey, message ->
        IngestionQueueClient.enqueue(IngestionPipeline.EVENTS, queueKey, message)
    }
    var isQuotaEnforcementEnabled: () -> Boolean = { quotaService.isEnforcementEnabled() }
    var reserveEnvelopeQuota: (Int, Map<String, Int>, Map<String, Long>) -> QuotaReservationResult =
        { orgId, unitsByType, bytesByType -> quotaService.reserveUnitsBatch(orgId, unitsByType, bytesByType) }
    var reserveLogQuota: (Int, Int, Long) -> QuotaReservationResult =
        { orgId, units, bytes -> quotaService.reserveUnits(orgId, units, "log", bytes) }
    var reserveSingleQuota: (Int, Int, String, Long) -> QuotaReservationResult =
        { orgId, units, eventType, bytes -> quotaService.reserveUnits(orgId, units, eventType, bytes) }
}

private fun sha256HexPrefix(bytes: ByteArray, maxHexChars: Int): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { b -> "%02x".format(b) }.take(maxHexChars)
}

fun Route.ingestRoutes(dependencies: IngestRouteDependencies = IngestRouteDependencies()) {
    route("/api/{projectId}") {
        post("/envelope/") {
            handleEnvelopeIngest(dependencies)
        }

        post("/logs/") {
            handleLogIngest(dependencies)
        }

        post("/store/") {
            handleStoreIngest(dependencies)
        }

        get("/security/") {
            call.respond(HttpStatusCode.OK)
        }
    }
}

private suspend fun RoutingContext.handleEnvelopeIngest(dependencies: IngestRouteDependencies) {
    val queueKey = call.application.environment.config.property("ingest.queueKey").getString()
    val projectId = call.parameters["projectId"]?.let(dependencies.projectIdResolver::resolveProtocolId)
    if (projectId == null) {
        call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
        return
    }
    val publicKey = extractPublicKey(call.request.header("X-Sentry-Auth"), call.request.queryParameters["sentry_key"])
    if (publicKey == null) {
        call.respond(HttpStatusCode.Unauthorized, "Missing or invalid authentication")
        return
    }
    val verification = dependencies.eventService.verifyProjectKey(projectId, publicKey)
    if (!verification.isValid) {
        call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
        return
    }

    suspendRunCatching {
        val contentEncoding = call.request.header("Content-Encoding")
        val bodyBytes = call.receive<ByteArray>()
        val decompressedBytes = DecompressionService.decompress(bodyBytes, contentEncoding)
        logger.debug { "Received envelope for project $projectId" }
        logger.debug {
            "Envelope payload (redacted): bytes=${decompressedBytes.size}, " +
                "sha256_prefix=${sha256HexPrefix(decompressedBytes, SHA256_HEX_PREFIX_CHARS)}"
        }
        val envelope = SentryEnvelope.parse(decompressedBytes)
        logger.debug { "Envelope parsed successfully, items: ${envelope.items.size}" }
        if (!reserveEnvelopeQuota(projectId, envelope, dependencies)) return
        dependencies.enqueueEnvelope(queueKey, IngestionWorker.encodeMessage(projectId, decompressedBytes))
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

private suspend fun RoutingContext.reserveEnvelopeQuota(
    projectId: Long,
    envelope: SentryEnvelope,
    dependencies: IngestRouteDependencies,
): Boolean {
    if (!dependencies.isQuotaEnforcementEnabled()) return true
    val orgId = dependencies.eventService.getOrganizationIdForProject(projectId)
    if (orgId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
        return false
    }
    val groupedReservations =
        envelope.items
            .groupingBy { mapEnvelopeItemToQuotaType(it) }
            .eachCount()
    val groupedBytes =
        envelope.items
            .groupBy { mapEnvelopeItemToQuotaType(it) }
            .mapValues { (_, items) ->
                items.sumOf { item ->
                    (item.payloadBytes?.size ?: item.payload.toByteArray(Charsets.UTF_8).size).toLong()
                }
            }
    val reservation = dependencies.reserveEnvelopeQuota(orgId, groupedReservations, groupedBytes)
    if (reservation.allowed) return true
    call.respond(
        HttpStatusCode.TooManyRequests,
        ErrorResponse("Quota exceeded: ${reservation.reason}")
    )
    return false
}

private suspend fun RoutingContext.handleLogIngest(dependencies: IngestRouteDependencies) {
    val projectId = call.parameters["projectId"]?.let(dependencies.projectIdResolver::resolveProtocolId)
    if (projectId == null) {
        call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
        return
    }
    val publicKey =
        extractPublicKey(call.request.header("X-Sentry-Auth"), call.request.queryParameters["sentry_key"])
            ?: extractPublicKeyFromDsn(call.request.header(HttpHeaders.Authorization))
            ?: extractPublicKeyFromDsn(call.request.header("x-moneat-dsn"))
    if (publicKey == null) {
        call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
        return
    }
    val verification = dependencies.eventService.verifyProjectKey(projectId, publicKey)
    if (!verification.isValid) {
        call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
        return
    }
    val organizationId = dependencies.eventService.getOrganizationIdForProject(projectId)
    if (organizationId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
        return
    }

    val entries = receiveLogEntries(projectId) ?: return
    if (entries.isEmpty()) {
        call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
        return
    }
    if (!reserveLogQuota(organizationId, entries, dependencies)) return
    val queueKey = call.application.environment.config.propertyOrNull("logs.queueKey")?.getString()
        ?: "moneat:logs:queue"
    val accepted = dependencies.logService.enqueueSdkLogs(organizationId.toLong(), entries, queueKey)
    call.respond(HttpStatusCode.Accepted, mapOf("accepted" to accepted))
}

private suspend fun RoutingContext.receiveLogEntries(projectId: Long): List<LogIngestEntry>? {
    val contentEncoding = call.request.header(HttpHeaders.ContentEncoding)
    val bodyBytes = call.receive<ByteArray>()
    val payloadBytes =
        if (contentEncoding == "gzip") {
            java.util.zip.GZIPInputStream(bodyBytes.inputStream()).readBytes()
        } else {
            bodyBytes
        }
    return suspendRunCatching {
        json.decodeFromString<List<LogIngestEntry>>(payloadBytes.decodeToString())
    }.getOrElse { e ->
        logger.warn(e) { "Invalid log payload for project $projectId" }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid log payload"))
        null
    }
}

private suspend fun RoutingContext.reserveLogQuota(
    organizationId: Int,
    entries: List<LogIngestEntry>,
    dependencies: IngestRouteDependencies,
): Boolean {
    if (!dependencies.isQuotaEnforcementEnabled()) return true
    val billableBytes = dependencies.logService.estimateBillableBytes(entries)
    val reservation = dependencies.reserveLogQuota(organizationId, entries.size, billableBytes)
    if (reservation.allowed) return true
    call.respond(
        HttpStatusCode.TooManyRequests,
        ErrorResponse("Quota exceeded: ${reservation.reason}")
    )
    return false
}

private suspend fun RoutingContext.handleStoreIngest(dependencies: IngestRouteDependencies) {
    val projectId = call.parameters["projectId"]?.let(dependencies.projectIdResolver::resolveProtocolId)
    if (projectId == null) {
        call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
        return
    }
    val publicKey = extractPublicKey(call.request.header("X-Sentry-Auth"), call.request.queryParameters["sentry_key"])
    if (publicKey == null) {
        call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
        return
    }
    val verification = dependencies.eventService.verifyProjectKey(projectId, publicKey)
    if (!verification.isValid) {
        call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
        return
    }

    val body = call.receiveText()
    logger.debug { "Received store event for project $projectId" }
    suspendRunCatching {
        if (!reserveStoreQuota(projectId, body, dependencies)) return
        dependencies.eventService.processStoreEvent(projectId, body)
        call.respond(HttpStatusCode.OK)
    }.getOrElse { e ->
        logger.error(e) { "Failed to process store event" }
        call.respond(HttpStatusCode.BadRequest, "Invalid event format")
    }
}

private suspend fun RoutingContext.reserveStoreQuota(
    projectId: Long,
    body: String,
    dependencies: IngestRouteDependencies,
): Boolean {
    if (!dependencies.isQuotaEnforcementEnabled()) return true
    val orgId = dependencies.eventService.getOrganizationIdForProject(projectId)
    if (orgId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project organization not found"))
        return false
    }
    val bodyBytes = body.toByteArray(Charsets.UTF_8).size.toLong()
    val quotaType = mapEnvelopeItemToQuotaType(EnvelopeItem("event", body))
    val reservation = dependencies.reserveSingleQuota(orgId, 1, quotaType, bodyBytes)
    if (reservation.allowed) return true
    call.respond(
        HttpStatusCode.TooManyRequests,
        ErrorResponse("Quota exceeded: ${reservation.reason}")
    )
    return false
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
    val regex = "https?://([a-zA-Z0-9_-]+)@[^/]+/$PROJECT_ID_DSN_SEGMENT_PATTERN(?:[/?#]|$)"
        .toRegex(RegexOption.IGNORE_CASE)
    return regex.find(cleaned)?.groupValues?.getOrNull(1)
}

internal fun mapEnvelopeItemTypeToQuotaType(itemType: String): String {
    return when (itemType) {
        "transaction" -> "transaction"
        "session", "sessions" -> "session"
        "replay_event", "replay_recording", "replay_video" -> "replay"
        "feedback", "user_report" -> "feedback"
        "llm_generation" -> "llm"
        else -> "error"
    }
}

internal fun mapEnvelopeItemToQuotaType(item: EnvelopeItem): String {
    if (item.isFeedbackEventPayload()) return "feedback"
    return mapEnvelopeItemTypeToQuotaType(item.type)
}
