package com.moneat.routes

import com.moneat.config.RedisConfig
import com.moneat.models.LogIngestEntry
import com.moneat.models.SentryEnvelope
import com.moneat.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

fun Route.ingestRoutes() {
    val emailService = EmailService()
    val notificationService = NotificationService(emailService)
    val eventService = EventService(notificationService)
    val quotaService = BillingQuotaService()
    val logService = LogService()

    route("/api/{projectId}") {
        // Sentry envelope endpoint (primary) - enqueue for async processing, respond 202
        post("/envelope/") {
            val queueKey = call.application.environment.config.property("ingest.queueKey").getString()
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
            try {
                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()

                val decompressedBytes = if (contentEncoding == "gzip") {
                    logger.debug { "Decompressing gzip envelope" }
                    java.util.zip.GZIPInputStream(bodyBytes.inputStream()).readBytes()
                } else {
                    bodyBytes
                }

                logger.debug { "Received envelope for project $projectId" }
                logger.debug { "Envelope body:\n${decompressedBytes.decodeToString().take(500)}" }

                val envelope = SentryEnvelope.parse(decompressedBytes)
                logger.debug { "Envelope parsed successfully, items: ${envelope.items.size}" }

                if (quotaService.isEnforcementEnabled()) {
                    val orgId = eventService.getOrganizationIdForProject(projectId)
                    if (orgId == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project organization not found"))
                        return@post
                    }
                    val groupedReservations = envelope.items
                        .groupingBy { mapEnvelopeItemTypeToQuotaType(it.type) }
                        .eachCount()

                    // Calculate bytes per type for GB quota tracking
                    val groupedBytes = envelope.items
                        .groupBy { mapEnvelopeItemTypeToQuotaType(it.type) }
                        .mapValues { (_, items) ->
                            items.sumOf { item ->
                                (item.payloadBytes?.size ?: item.payload.toByteArray(Charsets.UTF_8).size).toLong()
                            }
                        }

                    val reservation = quotaService.reserveUnitsBatch(orgId, groupedReservations, groupedBytes)
                    if (!reservation.allowed) {
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            mapOf(
                                "error" to "Quota exceeded",
                                "reason" to reservation.reason,
                                "eventType" to reservation.eventType,
                                "usage" to reservation.usage
                            )
                        )
                        return@post
                    }
                }

                val message = IngestionWorker.encodeMessage(projectId, decompressedBytes)
                RedisConfig.sync().lpush(queueKey, message)

                call.respond(HttpStatusCode.Accepted, mapOf("id" to envelope.eventId))
            } catch (e: Exception) {
                logger.error(e) { "Failed to process envelope: ${e.message}" }
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid envelope format", "message" to (e.message ?: "Unknown error")))
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
            val publicKey = extractPublicKey(authHeader, sentryKey)
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
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project organization not found"))
                return@post
            }

            val contentEncoding = call.request.header(HttpHeaders.ContentEncoding)
            val bodyBytes = call.receive<ByteArray>()
            val payloadBytes = if (contentEncoding == "gzip") {
                java.util.zip.GZIPInputStream(bodyBytes.inputStream()).readBytes()
            } else {
                bodyBytes
            }

            val entries = try {
                json.decodeFromString<List<LogIngestEntry>>(payloadBytes.decodeToString())
            } catch (e: Exception) {
                logger.warn(e) { "Invalid log payload for project $projectId" }
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid log payload"))
                return@post
            }

            if (entries.isEmpty()) {
                call.respond(HttpStatusCode.Accepted, mapOf("accepted" to 0))
                return@post
            }

            if (quotaService.isEnforcementEnabled()) {
                val billableBytes = logService.estimateBillableBytes(entries)
                val reservation = quotaService.reserveUnits(
                    organizationId = organizationId,
                    requestedUnits = entries.size,
                    eventType = "log",
                    requestedBytes = billableBytes
                )
                if (!reservation.allowed) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        mapOf(
                            "error" to "Quota exceeded",
                            "reason" to reservation.reason,
                            "usage" to reservation.usage
                        )
                    )
                    return@post
                }
            }

            val queueKey = call.application.environment.config.propertyOrNull("logs.queueKey")?.getString()
                ?: "moneat:logs:queue"
            val accepted = logService.enqueueSdkLogs(projectId, entries, queueKey)
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
            
            try {
                if (quotaService.isEnforcementEnabled()) {
                    val orgId = eventService.getOrganizationIdForProject(projectId)
                    if (orgId == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project organization not found"))
                        return@post
                    }
                    val bodyBytes = body.toByteArray(Charsets.UTF_8).size.toLong()
                    val reservation = quotaService.reserveUnits(orgId, 1, "error", bodyBytes)
                    if (!reservation.allowed) {
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            mapOf(
                                "error" to "Quota exceeded",
                                "reason" to reservation.reason,
                                "usage" to reservation.usage
                            )
                        )
                        return@post
                    }
                }

                eventService.processStoreEvent(projectId, body)
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
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

internal fun extractPublicKey(authHeader: String?, sentryKeyParam: String? = null): String? {
    val headerKey = authHeader?.let { header ->
        // Parse "Sentry sentry_key=xxx, sentry_version=7"
        val keyRegex = "(?i)sentry_key=([a-z0-9]+)".toRegex()
        keyRegex.find(header)?.groupValues?.get(1)
    }
    if (headerKey != null) return headerKey

    // Fallback for SDKs that pass auth in query params:
    // ?sentry_key=xxx&sentry_version=7&sentry_client=...
    val keyRegex = "^[a-zA-Z0-9]+$".toRegex()
    return sentryKeyParam?.takeIf { keyRegex.matches(it) }
}

internal fun extractPublicKeyFromDsn(dsnLikeHeader: String?): String? {
    if (dsnLikeHeader.isNullOrBlank()) return null
    val cleaned = dsnLikeHeader.removePrefix("DSN ").trim()
    val regex = "https?://([a-zA-Z0-9]+)@[^/]+/[0-9]+".toRegex(RegexOption.IGNORE_CASE)
    return regex.find(cleaned)?.groupValues?.getOrNull(1)
}

private fun mapEnvelopeItemTypeToQuotaType(itemType: String): String {
    return when (itemType) {
        "transaction" -> "transaction"
        "replay_event", "replay_recording", "replay_video" -> "replay"
        "feedback" -> "feedback"
        else -> "error"
    }
}
