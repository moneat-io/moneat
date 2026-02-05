package com.moneat.routes

import com.moneat.models.SentryEnvelope
import com.moneat.services.EventService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.ingestRoutes() {
    val eventService = EventService()
    
    route("/api/{projectId}") {
        // Sentry envelope endpoint (primary)
        post("/envelope/") {
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
            if (!eventService.verifyProjectKey(projectId, publicKey)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }
            
            // Parse envelope - handle gzip compression
            try {
                val contentEncoding = call.request.header("Content-Encoding")
                val bodyBytes = call.receive<ByteArray>()
                
                val bodyText = if (contentEncoding == "gzip") {
                    logger.debug { "Decompressing gzip envelope" }
                    java.util.zip.GZIPInputStream(bodyBytes.inputStream()).bufferedReader().use { it.readText() }
                } else {
                    bodyBytes.decodeToString()
                }
                
                logger.debug { "Received envelope for project $projectId" }
                logger.debug { "Envelope body:\n${bodyText.take(500)}" }
                
                val envelope = SentryEnvelope.parse(bodyText)
                logger.debug { "Envelope parsed successfully, items: ${envelope.items.size}" }
                eventService.processEnvelope(projectId, envelope)
                
                call.respond(HttpStatusCode.OK, mapOf("id" to envelope.eventId))
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
            
            if (publicKey == null || !eventService.verifyProjectKey(projectId, publicKey)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid DSN")
                return@post
            }
            
            val body = call.receiveText()
            logger.debug { "Received store event for project $projectId" }
            
            try {
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

private fun extractPublicKey(authHeader: String?): String? {
    if (authHeader == null) return null
    
    // Parse "Sentry sentry_key=xxx, sentry_version=7"
    val keyRegex = "sentry_key=([a-f0-9]+)".toRegex()
    return keyRegex.find(authHeader)?.groupValues?.get(1)
}
