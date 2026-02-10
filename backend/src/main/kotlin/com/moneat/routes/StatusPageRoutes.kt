package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.StatusPageService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * Helper to safely parse UUID from path parameter.
 * Returns null if invalid, allowing caller to return 400 Bad Request.
 */
private fun String.toUUIDOrNull(): UUID? {
    return try {
        UUID.fromString(this)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Helper to get organization IDs for a user.
 */
private fun getOrganizationIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships.selectAll().where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}

/**
 * Status page routes - both authenticated management and public endpoints.
 */
fun Route.statusPageRoutes() {
    val statusPageService = StatusPageService()
    
    // ==================== Authenticated Management Endpoints ====================
    
    route("/v1/status-pages") {
        authenticate("auth-jwt") {
            
            /**
             * List all status pages for organization.
             */
            get {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@get
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.OK, emptyList<StatusPageResponse>())
                        return@get
                    }
                    
                    // For simplicity, use the first organization
                    val organizationId = organizationIds.first()
                    val statusPages = statusPageService.listStatusPages(organizationId)
                    
                    call.respond(HttpStatusCode.OK, statusPages)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to list status pages" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list status pages"))
                }
            }
            
            /**
             * Create a new status page.
             */
            post {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@post
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@post
                    }
                    
                    val organizationId = organizationIds.first()
                    val request = call.receive<CreateStatusPageRequest>()
                    
                    val statusPage = statusPageService.createStatusPage(organizationId, request)
                    call.respond(HttpStatusCode.Created, statusPage)
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid status page creation request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create status page" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create status page"))
                }
            }
            
            /**
             * Get status page details.
             */
            get("/{pageId}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@get
                    }
                    
                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid page ID format"))
                        return@get
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@get
                    }
                    
                    val organizationId = organizationIds.first()
                    val statusPage = statusPageService.getStatusPage(pageId, organizationId)
                    
                    if (statusPage == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Status page not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, statusPage)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get status page" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get status page"))
                }
            }
            
            /**
             * Update status page.
             */
            put("/{pageId}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@put
                    }
                    
                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid page ID format"))
                        return@put
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@put
                    }
                    
                    val organizationId = organizationIds.first()
                    val request = call.receive<UpdateStatusPageRequest>()
                    
                    val statusPage = statusPageService.updateStatusPage(pageId, organizationId, request)
                    
                    if (statusPage == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Status page not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, statusPage)
                    }
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid status page update request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to update status page" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update status page"))
                }
            }
            
            /**
             * Delete status page.
             */
            delete("/{pageId}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@delete
                    }
                    
                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid page ID format"))
                        return@delete
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@delete
                    }
                    
                    val organizationId = organizationIds.first()
                    val deleted = statusPageService.deleteStatusPage(pageId, organizationId)
                    
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Status page not found"))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete status page" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete status page"))
                }
            }
            
            /**
             * Add/reorder monitors.
             */
            post("/{pageId}/monitors") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    
                    if (userId == null || pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameters"))
                        return@post
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@post
                    }
                    
                    val organizationId = organizationIds.first()
                    val request = call.receive<AddMonitorsRequest>()
                    
                    val monitors = statusPageService.addMonitors(pageId, organizationId, request)
                    call.respond(HttpStatusCode.OK, monitors)
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid monitor assignment request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to add monitors" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to add monitors"))
                }
            }
            
            /**
             * Remove monitor from status page.
             */
            delete("/{pageId}/monitors/{monitorId}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val monitorId = call.parameters["monitorId"]?.toUUIDOrNull()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@delete
                    }
                    
                    if (pageId == null || monitorId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid UUID format in path parameters"))
                        return@delete
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@delete
                    }
                    
                    val organizationId = organizationIds.first()
                    val removed = statusPageService.removeMonitor(pageId, organizationId, monitorId)
                    
                    if (removed) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Monitor not found on status page"))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to remove monitor" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to remove monitor"))
                }
            }
            
            /**
             * List incidents for a status page.
             */
            get("/{pageId}/incidents") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    
                    if (userId == null || pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameters"))
                        return@get
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@get
                    }
                    
                    val organizationId = organizationIds.first()
                    val incidents = statusPageService.listIncidents(pageId, organizationId)
                    
                    call.respond(HttpStatusCode.OK, incidents)
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid incident list request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to list incidents" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list incidents"))
                }
            }
            
            /**
             * Create incident.
             */
            post("/{pageId}/incidents") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    
                    if (userId == null || pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameters"))
                        return@post
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@post
                    }
                    
                    val organizationId = organizationIds.first()
                    val request = call.receive<CreateIncidentRequest>()
                    
                    val incident = statusPageService.createIncident(pageId, organizationId, request)
                    call.respond(HttpStatusCode.Created, incident)
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid incident creation request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create incident" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create incident"))
                }
            }
            
            /**
             * Update incident.
             */
            put("/{pageId}/incidents/{incidentId}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val incidentId = call.parameters["incidentId"]?.toUUIDOrNull()
                    
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                        return@put
                    }
                    
                    if (pageId == null || incidentId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid UUID format in path parameters"))
                        return@put
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@put
                    }
                    
                    val organizationId = organizationIds.first()
                    val request = call.receive<UpdateIncidentRequest>()
                    
                    val incident = statusPageService.updateIncident(pageId, organizationId, incidentId, request)
                    
                    if (incident == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, incident)
                    }
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid incident update request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to update incident" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update incident"))
                }
            }
            
            /**
             * Post incident update.
             */
            post("/{pageId}/incidents/{incidentId}/updates") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val incidentId = call.parameters["incidentId"]?.toUUIDOrNull()
                    
                    if (userId == null || pageId == null || incidentId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameters"))
                        return@post
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@post
                    }
                    
                    val organizationId = organizationIds.first()
                    val request = call.receive<CreateIncidentUpdateRequest>()
                    
                    val incident = statusPageService.createIncidentUpdate(pageId, organizationId, incidentId, request)
                    
                    if (incident == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Incident not found"))
                    } else {
                        call.respond(HttpStatusCode.Created, incident)
                    }
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid incident update request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create incident update" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create incident update"))
                }
            }
            
            /**
             * Add custom domain.
             */
            post("/{pageId}/domains") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    
                    if (userId == null || pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameters"))
                        return@post
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@post
                    }
                    
                    val organizationId = organizationIds.first()
                    val request = call.receive<AddCustomDomainRequest>()
                    
                    val domain = statusPageService.addCustomDomain(pageId, organizationId, request)
                    call.respond(HttpStatusCode.Created, domain)
                } catch (e: IllegalArgumentException) {
                    logger.warn(e) { "Invalid custom domain request" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to add custom domain" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to add custom domain"))
                }
            }
            
            /**
             * Verify custom domain.
             */
            post("/{pageId}/domains/{domainId}/verify") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val domainId = call.parameters["domainId"]?.toIntOrNull()
                    
                    if (userId == null || pageId == null || domainId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameters"))
                        return@post
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@post
                    }
                    
                    val organizationId = organizationIds.first()
                    val domain = statusPageService.verifyCustomDomain(pageId, organizationId, domainId)
                    
                    if (domain == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Domain not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, domain)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to verify custom domain" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to verify custom domain"))
                }
            }
            
            /**
             * Remove custom domain.
             */
            delete("/{pageId}/domains/{domainId}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val domainId = call.parameters["domainId"]?.toIntOrNull()
                    
                    if (userId == null || pageId == null || domainId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid parameters"))
                        return@delete
                    }
                    
                    val organizationIds = getOrganizationIdsForUser(userId)
                    if (organizationIds.isEmpty()) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organization found"))
                        return@delete
                    }
                    
                    val organizationId = organizationIds.first()
                    val removed = statusPageService.removeCustomDomain(pageId, organizationId, domainId)
                    
                    if (removed) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Domain not found"))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to remove custom domain" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to remove custom domain"))
                }
            }
        }
    }
    
    // ==================== Public Endpoints ====================
    
    route("/public/status") {
        /**
         * Get public status page by slug.
         */
        get("/{slug}") {
            try {
                val slug = call.parameters["slug"]
                
                if (slug == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing slug"))
                    return@get
                }
                
                val statusPage = statusPageService.getPublicStatusPage(slug)
                
                if (statusPage == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Status page not found"))
                } else {
                    call.respond(HttpStatusCode.OK, statusPage)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get public status page" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get status page"))
            }
        }
        
        /**
         * Get public status page by custom domain.
         */
        get("/domain/{domain}") {
            try {
                val domain = call.parameters["domain"]
                
                if (domain == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing domain"))
                    return@get
                }
                
                val statusPage = statusPageService.getPublicStatusPageByDomain(domain)
                
                if (statusPage == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Status page not found"))
                } else {
                    call.respond(HttpStatusCode.OK, statusPage)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get public status page by domain" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get status page"))
            }
        }
    }
}
