package com.moneat.routes

import com.moneat.config.RedisConfig
import com.moneat.models.SentryEnvelope
import com.moneat.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.ingestRoutes() {
    val emailService = EmailService()
    val notificationService = NotificationService(emailService)
    val eventService = EventService(notificationService)
    val quotaService = BillingQuotaService()

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
            val publicKey = extractPublicKey(authHeader)

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

                    val reservation = quotaService.reserveUnitsBatch(orgId, groupedReservations)
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
        
        // Legacy store endpoint
        post("/store/") {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }
            
            val authHeader = call.request.header("X-Sentry-Auth")
            val publicKey = extractPublicKey(authHeader)

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
                    val reservation = quotaService.reserveUnits(orgId, 1, "error")
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

internal fun extractPublicKey(authHeader: String?): String? {
    if (authHeader == null) return null
    
    // Parse "Sentry sentry_key=xxx, sentry_version=7"
    val keyRegex = "sentry_key=([a-f0-9]+)".toRegex()
    return keyRegex.find(authHeader)?.groupValues?.get(1)
}

private fun mapEnvelopeItemTypeToQuotaType(itemType: String): String {
    return when (itemType) {
        "transaction" -> "transaction"
        "replay_event", "replay_recording", "replay_video" -> "replay"
        "feedback" -> "feedback"
        else -> "error"
    }
}
