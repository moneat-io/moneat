// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.alerts.models.IncidentSeverity
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.services.OnCallAlertService
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val DEFAULT_INCIDENT_LIMIT = 50
private const val MIN_INCIDENT_LIMIT = 1
private const val MAX_INCIDENT_LIMIT = 200
private const val INVALID_TOKEN_MESSAGE = "Invalid token"
private const val INVALID_ALERT_ID_MESSAGE = "Invalid alert ID"
private const val ALERT_NOT_FOUND_MESSAGE = "Alert not found"
private const val INVALID_INCIDENT_ID_MESSAGE = "Invalid incident ID"
private const val INCIDENT_NOT_FOUND_MESSAGE = "Incident not found"

private data class OnCallUserContext(
    val organizationId: Int,
    val userId: Int,
)

private fun parseStatusFilters(rawStatuses: List<String>?): List<String> =
    rawStatuses
        .orEmpty()
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

@Serializable
data class DeclareIncidentRequest(
    val title: String,
    val description: String? = null,
    val severity: String? = null,
)

@Serializable
data class AddAlertToIncidentRequest(
    val alertId: String,
)

@Serializable
data class ReassignIncidentRequest(
    val toUserId: String,
)

@Serializable
data class AddNoteRequest(
    val note: String,
)

@Serializable
data class ResolveIncidentRequest(
    val note: String? = null,
)

fun Route.incidentRoutes(alertServiceProvider: () -> OnCallAlertService) {
    val onCallIncidentService = OnCallIncidentService()
    registerAlertRoutes(alertServiceProvider, onCallIncidentService)
    registerDeclaredIncidentRoutes(alertServiceProvider, onCallIncidentService)
}

private fun Route.registerAlertRoutes(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService,
) {
    route("/v1/on-call/alerts") {
        authenticate("auth-jwt") {
            registerListAlertsRoute(alertServiceProvider)
            registerGetAlertRoute(alertServiceProvider)
            registerAlertTimelineRoute(alertServiceProvider)
            registerAcknowledgeAlertRoute(alertServiceProvider)
            registerResolveAlertRoute(alertServiceProvider)
            registerReassignAlertRoute(alertServiceProvider)
            registerAddAlertNoteRoute(alertServiceProvider)
            registerViewAlertRoute(alertServiceProvider)
            registerUnavailableAlertRoute(alertServiceProvider)
            registerDeclareIncidentFromAlertRoute(alertServiceProvider, onCallIncidentService)
        }
    }
}

private fun Route.registerDeclaredIncidentRoutes(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService,
) {
    route("/v1/on-call/incidents") {
        authenticate("auth-jwt") {
            registerListDeclaredIncidentsRoute(onCallIncidentService)
            registerGetDeclaredIncidentRoute(onCallIncidentService)
            registerResolveDeclaredIncidentRoute(onCallIncidentService)
            registerAddAlertToIncidentRoute(alertServiceProvider, onCallIncidentService)
            registerIncidentTimelineRoute(onCallIncidentService)
            registerAddIncidentNoteRoute(onCallIncidentService)
        }
    }
}

private suspend fun ApplicationCall.requireOrganizationId(): Int? {
    val organizationId = principal<JWTPrincipal>()?.currentOrgIdOrNull()
    if (organizationId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
    }
    return organizationId
}

private suspend fun ApplicationCall.requireUserContext(): OnCallUserContext? {
    val principal = principal<JWTPrincipal>()
    val organizationId = principal?.currentOrgIdOrNull()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    if (organizationId == null || userId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
        return null
    }
    return OnCallUserContext(organizationId, userId)
}

private suspend fun ApplicationCall.requireAlertId(
    organizationId: Int,
    parameterName: String = "id",
): Int? =
    requireAlertIdValue(parameters[parameterName], organizationId)

private suspend fun ApplicationCall.requireIncidentId(organizationId: Int): Int? =
    requireIncidentIdValue(parameters["id"], organizationId)

private suspend fun ApplicationCall.requireAlertIdValue(
    raw: String?,
    organizationId: Int,
): Int? {
    val resourceId = parseOnCallResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_ALERT_ID_MESSAGE))
        return null
    }
    val id =
        transaction {
            OnCallAlerts
                .selectAll()
                .where {
                    (OnCallAlerts.resourceId eq resourceId) and
                        (OnCallAlerts.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(OnCallAlerts.id)
                ?.value
        }
    if (id == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
    }
    return id
}

private suspend fun ApplicationCall.requireIncidentIdValue(
    raw: String?,
    organizationId: Int,
): Int? {
    val resourceId = parseOnCallResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_INCIDENT_ID_MESSAGE))
        return null
    }
    val id =
        transaction {
            OnCallIncidents
                .selectAll()
                .where {
                    (OnCallIncidents.resourceId eq resourceId) and
                        (OnCallIncidents.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(OnCallIncidents.id)
                ?.value
        }
    if (id == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(INCIDENT_NOT_FOUND_MESSAGE))
    }
    return id
}

private fun parseOnCallResourceId(raw: String?): Uuid? =
    raw?.toUuidOrNull()

private suspend fun ApplicationCall.requireReassignmentUserId(
    organizationId: Int,
    raw: String,
): Int? {
    val resourceId = parseOnCallResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
        return null
    }
    val userId =
        transaction {
            Users
                .innerJoin(Memberships)
                .selectAll()
                .where {
                    (Users.resource_id eq resourceId) and
                        (Memberships.organization_id eq organizationId)
                }
                .firstOrNull()
                ?.get(Users.id)
        }
    if (userId == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
    }
    return userId
}

private suspend fun ApplicationCall.ensureAlertInOrganization(
    alertService: OnCallAlertService,
    alertId: Int,
    organizationId: Int,
): Boolean {
    val alert = alertService.getAlert(alertId)
    if (alert == null || alert.organizationId != organizationId) {
        respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
        return false
    }
    return true
}

private suspend fun ApplicationCall.ensureIncidentInOrganization(
    onCallIncidentService: OnCallIncidentService,
    incidentId: Int,
    organizationId: Int,
): Boolean {
    if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
        respond(HttpStatusCode.NotFound, ErrorResponse(INCIDENT_NOT_FOUND_MESSAGE))
        return false
    }
    return true
}

private fun Route.registerListAlertsRoute(alertServiceProvider: () -> OnCallAlertService) {
    get {
        val context = call.requireUserContext() ?: return@get
        val statuses = parseStatusFilters(call.request.queryParameters.getAll("status"))
        val priority = call.request.queryParameters["priority"]
        val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull()
        val limit = parseLimit(rawLimit)
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val alerts =
            alertServiceProvider().listAlerts(
                organizationId = context.organizationId,
                statuses = statuses.ifEmpty { null },
                priority = priority,
                limit = limit,
                offset = offset,
                currentUserId = context.userId,
            )
        call.respond(alerts)
    }
}

private fun parseLimit(rawLimit: Int?): Int =
    when {
        rawLimit == null -> DEFAULT_INCIDENT_LIMIT
        rawLimit < MIN_INCIDENT_LIMIT -> throw BadRequestException("limit must be >= $MIN_INCIDENT_LIMIT")
        else -> rawLimit.coerceAtMost(MAX_INCIDENT_LIMIT)
    }

private fun Route.registerGetAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    get("/{id}") {
        val context = call.requireUserContext() ?: return@get
        val alertId = call.requireAlertId(context.organizationId) ?: return@get
        val alert = alertServiceProvider().getAlert(alertId, context.userId)
        if (alert != null && alert.organizationId == context.organizationId) {
            call.respond(alert)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
        }
    }
}

private fun Route.registerAlertTimelineRoute(alertServiceProvider: () -> OnCallAlertService) {
    get("/{id}/timeline") {
        val organizationId = call.requireOrganizationId() ?: return@get
        val alertId = call.requireAlertId(organizationId) ?: return@get
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, organizationId)) return@get
        call.respond(alertService.getTimeline(alertId))
    }
}

private fun Route.registerAcknowledgeAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/acknowledge") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val acknowledged = alertService.acknowledge(alertId, context.userId)
        if (acknowledged) {
            call.respond(HttpStatusCode.OK, MessageResponse("Alert acknowledged"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not acknowledge alert"))
        }
    }
}

private fun Route.registerResolveAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/resolve") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val resolved = alertService.resolve(alertId, context.userId)
        if (resolved) {
            call.respond(HttpStatusCode.OK, MessageResponse("Alert resolved"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not resolve alert"))
        }
    }
}

private fun Route.registerReassignAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/reassign") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val request = call.receive<ReassignIncidentRequest>()
        val toUserId = call.requireReassignmentUserId(context.organizationId, request.toUserId) ?: return@post
        val reassigned = alertService.reassign(alertId, toUserId, context.userId)
        if (reassigned) {
            call.respond(HttpStatusCode.OK, MessageResponse("Alert reassigned"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not reassign alert"))
        }
    }
}

private fun Route.registerAddAlertNoteRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/notes") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val request = call.receive<AddNoteRequest>()
        val event = alertService.addNote(alertId, context.userId, request.note)
        call.respond(HttpStatusCode.Created, event)
    }
}

private fun Route.registerViewAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/view") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        alertService.viewAlert(alertId, context.userId)
        call.respond(HttpStatusCode.OK, MessageResponse("Alert viewed"))
    }
}

private fun Route.registerUnavailableAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/unavailable") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val result = alertService.markUnavailable(alertId, context.userId)
        if (result) {
            call.respond(HttpStatusCode.OK, MessageResponse("Escalated to next on-call"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not escalate alert"))
        }
    }
}

private fun Route.registerDeclareIncidentFromAlertRoute(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService,
) {
    post("/{alertId}/declare-incident") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId, "alertId") ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val request = call.receive<DeclareIncidentRequest>()
        val incidentSeverity = call.requireIncidentSeverity(request.severity) ?: return@post

        try {
            val incident =
                onCallIncidentService.declareIncident(
                    organizationId = context.organizationId,
                    userId = context.userId,
                    alertId = alertId,
                    title = request.title,
                    description = request.description,
                    severity = incidentSeverity,
                )
            call.respond(HttpStatusCode.Created, incident)
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
    }
}

private suspend fun ApplicationCall.requireIncidentSeverity(severity: String?): String? {
    val trimmedSeverity = severity?.trim()
    if (trimmedSeverity.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Missing incident severity"))
        return null
    }

    val incidentSeverity = IncidentSeverity.wireValue(trimmedSeverity)
    if (incidentSeverity == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident severity"))
        return null
    }
    return incidentSeverity
}

private fun Route.registerListDeclaredIncidentsRoute(onCallIncidentService: OnCallIncidentService) {
    get {
        val organizationId = call.requireOrganizationId() ?: return@get
        val status = call.request.queryParameters["status"]
        val severity = call.request.queryParameters["severity"]
        val incidents = onCallIncidentService.getIncidents(organizationId, status, severity)
        call.respond(incidents)
    }
}

private fun Route.registerGetDeclaredIncidentRoute(onCallIncidentService: OnCallIncidentService) {
    get("/{id}") {
        val organizationId = call.requireOrganizationId() ?: return@get
        val incidentId = call.requireIncidentId(organizationId) ?: return@get
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, organizationId)) return@get
        val incident = onCallIncidentService.getIncident(incidentId)
        if (incident != null) {
            call.respond(incident)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(INCIDENT_NOT_FOUND_MESSAGE))
        }
    }
}

private fun Route.registerResolveDeclaredIncidentRoute(onCallIncidentService: OnCallIncidentService) {
    post("/{id}/resolve") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, context.organizationId)) return@post
        val request = call.receiveNullable<ResolveIncidentRequest>() ?: ResolveIncidentRequest()
        val incident = onCallIncidentService.resolveIncident(incidentId, context.userId, request.note)
        if (incident != null) {
            call.respond(incident)
        } else {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to resolve incident"))
        }
    }
}

private fun Route.registerAddAlertToIncidentRoute(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService,
) {
    post("/{id}/add-alert") {
        val organizationId = call.requireOrganizationId() ?: return@post
        val incidentId = call.requireIncidentId(organizationId) ?: return@post
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, organizationId)) return@post
        val request = call.receive<AddAlertToIncidentRequest>()
        val alertId = call.requireAlertIdValue(request.alertId, organizationId) ?: return@post
        val alert = alertServiceProvider().getAlert(alertId)
        if (alert == null || alert.organizationId != organizationId) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Alert not found or not in organization"))
            return@post
        }

        try {
            onCallIncidentService.addAlertToIncident(incidentId, alertId)
            call.respond(HttpStatusCode.OK, MessageResponse("Alert added to incident"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
    }
}

private fun Route.registerIncidentTimelineRoute(onCallIncidentService: OnCallIncidentService) {
    get("/{id}/timeline") {
        val organizationId = call.requireOrganizationId() ?: return@get
        val incidentId = call.requireIncidentId(organizationId) ?: return@get
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, organizationId)) return@get
        val timeline = onCallIncidentService.getIncidentTimeline(incidentId)
        call.respond(timeline)
    }
}

private fun Route.registerAddIncidentNoteRoute(onCallIncidentService: OnCallIncidentService) {
    post("/{id}/notes") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, context.organizationId)) return@post
        val request = call.receive<AddNoteRequest>()
        onCallIncidentService.addNote(incidentId, context.userId, request.note)
        call.respond(HttpStatusCode.OK, MessageResponse("Note added"))
    }
}
