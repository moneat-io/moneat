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

package com.moneat.statuspage.routes

import com.moneat.auth.requireCurrentOrg
import com.moneat.statuspage.models.AddCustomDomainRequest
import com.moneat.statuspage.models.AddMonitorsRequest
import com.moneat.statuspage.models.CreateIncidentRequest
import com.moneat.statuspage.models.CreateIncidentUpdateRequest
import com.moneat.statuspage.models.CreateStatusPageRequest
import com.moneat.statuspage.models.UpdateIncidentRequest
import com.moneat.statuspage.models.UpdateStatusPageRequest
import com.moneat.statuspage.services.StatusPageService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import java.util.UUID

private val logger = KotlinLogging.logger {}
private const val FAILED_TO_GET_STATUS_PAGE = "Failed to get status page"

private suspend fun runStatusPageRoute(
    call: ApplicationCall,
    logMessage: String,
    userMessage: String,
    block: suspend () -> Unit,
) {
    suspendRunCatching {
        block()
    }.onFailure { e ->
        logger.error(e) { "$logMessage: ${e.message}" }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(userMessage))
    }
}

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
 * Status page routes - both authenticated management and public endpoints.
 */
fun Route.statusPageRoutes(
    statusPageService: StatusPageService = GlobalContext.get().get(),
) {
    // ==================== Authenticated Management Endpoints ====================

    route("/v1/status-pages") {
        authenticate("auth-jwt") {
            /**
             * List all status pages for organization.
             */
            get {
                runStatusPageRoute(call, "Failed to list status pages", "Failed to list status pages") {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@runStatusPageRoute
                    val statusPages = statusPageService.listStatusPages(organizationId)

                    call.respond(HttpStatusCode.OK, statusPages)
                }
            }

            /**
             * Create a new status page.
             */
            post {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@post
                    val request = call.receive<CreateStatusPageRequest>()

                    val statusPage = statusPageService.createStatusPage(organizationId, request)
                    call.respond(HttpStatusCode.Created, statusPage)
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid status page creation request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to create status page" }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse("Failed to create status page")
                            )
                        }
                    }
                }
            }

            /**
             * Get status page details.
             */
            get("/{pageId}") {
                runStatusPageRoute(call, FAILED_TO_GET_STATUS_PAGE, FAILED_TO_GET_STATUS_PAGE) {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@runStatusPageRoute
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()

                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid page ID format"))
                        return@runStatusPageRoute
                    }

                    val statusPage = statusPageService.getStatusPage(pageId, organizationId)

                    if (statusPage == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Status page not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, statusPage)
                    }
                }
            }

            /**
             * Update status page.
             */
            put("/{pageId}") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@put
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()

                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid page ID format"))
                        return@put
                    }

                    val request = call.receive<UpdateStatusPageRequest>()

                    val statusPage = statusPageService.updateStatusPage(pageId, organizationId, request)

                    if (statusPage == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Status page not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, statusPage)
                    }
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid status page update request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to update status page" }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse("Failed to update status page")
                            )
                        }
                    }
                }
            }

            /**
             * Delete status page.
             */
            delete("/{pageId}") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@delete
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()

                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid page ID format"))
                        return@delete
                    }

                    val deleted = statusPageService.deleteStatusPage(pageId, organizationId)

                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Status page not found"))
                    }
                }.onFailure { e ->
                    logger.error(e) { "Failed to delete status page" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to delete status page"))
                }
            }

            /**
             * Add/reorder monitors.
             */
            post("/{pageId}/monitors") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@post
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()

                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parameters"))
                        return@post
                    }

                    val request = call.receive<AddMonitorsRequest>()

                    val monitors = statusPageService.addMonitors(pageId, organizationId, request)
                    call.respond(HttpStatusCode.OK, monitors)
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid monitor assignment request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to add monitors" }
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to add monitors"))
                        }
                    }
                }
            }

            /**
             * Remove monitor from status page.
             */
            delete("/{pageId}/monitors/{monitorId}") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@delete
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val monitorId = call.parameters["monitorId"]?.toUUIDOrNull()

                    if (pageId == null || monitorId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid UUID format in path parameters"))
                        return@delete
                    }

                    val removed = statusPageService.removeMonitor(pageId, organizationId, monitorId)

                    if (removed) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Monitor not found on status page"))
                    }
                }.onFailure { e ->
                    logger.error(e) { "Failed to remove monitor" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to remove monitor"))
                }
            }

            /**
             * List incidents for a status page.
             */
            get("/{pageId}/incidents") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@get
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()

                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parameters"))
                        return@get
                    }

                    val incidents = statusPageService.listIncidents(pageId, organizationId)

                    call.respond(HttpStatusCode.OK, incidents)
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid incident list request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to list incidents" }
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to list incidents"))
                        }
                    }
                }
            }

            /**
             * Create incident.
             */
            post("/{pageId}/incidents") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@post
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()

                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parameters"))
                        return@post
                    }

                    val request = call.receive<CreateIncidentRequest>()

                    val incident = statusPageService.createIncident(pageId, organizationId, request)
                    call.respond(HttpStatusCode.Created, incident)
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid incident creation request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to create incident" }
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to create incident"))
                        }
                    }
                }
            }

            /**
             * Update incident.
             */
            put("/{pageId}/incidents/{incidentId}") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@put
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val incidentId = call.parameters["incidentId"]?.toUUIDOrNull()

                    if (pageId == null || incidentId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid UUID format in path parameters"))
                        return@put
                    }

                    val request = call.receive<UpdateIncidentRequest>()

                    val incident = statusPageService.updateIncident(pageId, organizationId, incidentId, request)

                    if (incident == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, incident)
                    }
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid incident update request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to update incident" }
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to update incident"))
                        }
                    }
                }
            }

            /**
             * Post incident update.
             */
            post("/{pageId}/incidents/{incidentId}/updates") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@post
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val incidentId = call.parameters["incidentId"]?.toUUIDOrNull()

                    if (pageId == null || incidentId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parameters"))
                        return@post
                    }

                    val request = call.receive<CreateIncidentUpdateRequest>()

                    val incident = statusPageService.createIncidentUpdate(pageId, organizationId, incidentId, request)

                    if (incident == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
                    } else {
                        call.respond(HttpStatusCode.Created, incident)
                    }
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid incident update request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to create incident update" }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse("Failed to create incident update")
                            )
                        }
                    }
                }
            }

            /**
             * Add custom domain.
             */
            post("/{pageId}/domains") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@post
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()

                    if (pageId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parameters"))
                        return@post
                    }

                    val request = call.receive<AddCustomDomainRequest>()

                    val domain = statusPageService.addCustomDomain(pageId, organizationId, request)
                    call.respond(HttpStatusCode.Created, domain)
                }.onFailure { e ->
                    when (e) {
                        is IllegalArgumentException -> {
                            logger.warn(e) { "Invalid custom domain request" }
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse((e.message ?: "Invalid request")))
                        }
                        else -> {
                            logger.error(e) { "Failed to add custom domain" }
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse("Failed to add custom domain")
                            )
                        }
                    }
                }
            }

            /**
             * Verify custom domain.
             */
            post("/{pageId}/domains/{domainId}/verify") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@post
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val domainId = call.parameters["domainId"]?.toIntOrNull()

                    if (pageId == null || domainId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parameters"))
                        return@post
                    }

                    val domain = statusPageService.verifyCustomDomain(pageId, organizationId, domainId)

                    if (domain == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Domain not found"))
                    } else {
                        call.respond(HttpStatusCode.OK, domain)
                    }
                }.onFailure { e ->
                    logger.error(e) { "Failed to verify custom domain" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to verify custom domain"))
                }
            }

            /**
             * Remove custom domain.
             */
            delete("/{pageId}/domains/{domainId}") {
                suspendRunCatching {
                    val organizationId = call.requireCurrentOrg()?.orgId ?: return@delete
                    val pageId = call.parameters["pageId"]?.toUUIDOrNull()
                    val domainId = call.parameters["domainId"]?.toIntOrNull()

                    if (pageId == null || domainId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parameters"))
                        return@delete
                    }

                    val removed = statusPageService.removeCustomDomain(pageId, organizationId, domainId)

                    if (removed) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Domain not found"))
                    }
                }.onFailure { e ->
                    logger.error(e) { "Failed to remove custom domain" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to remove custom domain"))
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
            runStatusPageRoute(call, "Failed to get public status page", FAILED_TO_GET_STATUS_PAGE) {
                val slug = call.parameters["slug"]

                if (slug == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing slug"))
                    return@runStatusPageRoute
                }

                val statusPage = statusPageService.getPublicStatusPage(slug)

                if (statusPage == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Status page not found"))
                } else {
                    call.respond(HttpStatusCode.OK, statusPage)
                }
            }
        }

        /**
         * Get public status page by custom domain.
         */
        get("/domain/{domain}") {
            runStatusPageRoute(call, "Failed to get public status page by domain", FAILED_TO_GET_STATUS_PAGE) {
                val domain = call.parameters["domain"]

                if (domain == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing domain"))
                    return@runStatusPageRoute
                }

                val statusPage = statusPageService.getPublicStatusPageByDomain(domain)

                if (statusPage == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Status page not found"))
                } else {
                    call.respond(HttpStatusCode.OK, statusPage)
                }
            }
        }
    }
}
