// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.incidents.models.IncidentTimelineVisibility
import com.moneat.enterprise.incidents.timeline.EditIncidentTimelineEvent
import com.moneat.enterprise.incidents.timeline.AnnotateIncidentTimelineEvent
import com.moneat.enterprise.incidents.timeline.IncidentTimelineFilters
import com.moneat.enterprise.incidents.timeline.IncidentTimelineService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

@Serializable
data class EditIncidentTimelineEventRequest(
    val eventType: String? = null,
    val details: Map<String, JsonElement>? = null,
    val originalOccurredAt: String? = null,
    val visibility: String? = null,
    val reason: String? = null,
)

@Serializable
data class AnnotateIncidentTimelineEventRequest(
    val annotation: String? = null,
    val reason: String? = null,
)

@Serializable
data class ReorderIncidentTimelineRequest(
    val eventIds: List<String>,
    val reason: String? = null,
)

@Serializable
data class IncidentTimelineMutationReasonRequest(
    val reason: String? = null,
)

internal fun Route.registerIncidentTimelineRoutes(service: IncidentTimelineService) {
    registerIncidentTimelineReadRoutes(service)
    registerIncidentTimelineEditRoutes(service)
    registerIncidentTimelineRemovalRoutes(service)
}

private fun Route.registerIncidentTimelineReadRoutes(service: IncidentTimelineService) {
    get("/{id}/timeline") {
        val context = call.requireUserContext() ?: return@get
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@get
        call.respondTimelineFailure {
            call.respond(service.list(context.organizationId, incidentId, call.timelineFilters()))
        }
    }
    get("/{id}/timeline/export") {
        val context = call.requireUserContext() ?: return@get
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@get
        call.respondTimelineFailure {
            call.respond(service.export(context.organizationId, incidentId))
        }
    }
    get("/{id}/timeline/{eventId}/revisions") {
        val context = call.requireUserContext() ?: return@get
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@get
        call.respondTimelineFailure {
            call.respond(
                service.revisions(
                    context.organizationId,
                    incidentId,
                    call.parameters["eventId"].orEmpty(),
                ),
            )
        }
    }
}

private fun Route.registerIncidentTimelineEditRoutes(service: IncidentTimelineService) {
    patch("/{id}/timeline/{eventId}") {
        val context = call.requireUserContext() ?: return@patch
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@patch
        val request = call.receive<EditIncidentTimelineEventRequest>()
        call.respondTimelineFailure {
            call.respond(
                service.edit(
                    organizationId = context.organizationId,
                    incidentId = incidentId,
                    eventResourceId = call.parameters["eventId"].orEmpty(),
                    actorUserId = context.userId,
                    request = EditIncidentTimelineEvent(
                        eventType = request.eventType,
                        details = request.details,
                        originalOccurredAt = request.originalOccurredAt?.let(Instant::parse),
                        visibility = request.visibility?.let(::timelineVisibility),
                        reason = request.reason,
                    ),
                ),
            )
        }
    }
    post("/{id}/timeline/{eventId}/annotation") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        val request = call.receive<AnnotateIncidentTimelineEventRequest>()
        call.respondTimelineFailure {
            call.respond(
                service.annotate(
                    context.organizationId,
                    incidentId,
                    call.parameters["eventId"].orEmpty(),
                    context.userId,
                    AnnotateIncidentTimelineEvent(request.annotation, request.reason),
                ),
            )
        }
    }
    post("/{id}/timeline/reorder") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        val request = call.receive<ReorderIncidentTimelineRequest>()
        call.respondTimelineFailure {
            call.respond(
                service.reorder(
                    context.organizationId,
                    incidentId,
                    context.userId,
                    request.eventIds,
                    request.reason,
                ),
            )
        }
    }
}

private fun Route.registerIncidentTimelineRemovalRoutes(service: IncidentTimelineService) {
    delete("/{id}/timeline/{eventId}") {
        val context = call.requireUserContext() ?: return@delete
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@delete
        val request = call.receiveNullable<IncidentTimelineMutationReasonRequest>()
        call.respondTimelineFailure {
            service.delete(
                context.organizationId,
                incidentId,
                call.parameters["eventId"].orEmpty(),
                context.userId,
                request?.reason,
            )
            call.respond(HttpStatusCode.OK, MessageResponse("Timeline event deleted"))
        }
    }
    post("/{id}/timeline/{eventId}/restore") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        val request = call.receiveNullable<IncidentTimelineMutationReasonRequest>()
        call.respondTimelineFailure {
            service.restore(
                context.organizationId,
                incidentId,
                call.parameters["eventId"].orEmpty(),
                context.userId,
                request?.reason,
            )
            call.respond(HttpStatusCode.OK, MessageResponse("Timeline event restored"))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.timelineFilters(): IncidentTimelineFilters =
    IncidentTimelineFilters(
        eventTypes = request.queryParameters.getAll("eventType").orEmpty(),
        provenance = request.queryParameters.getAll("provenance").orEmpty().map(::timelineProvenance),
        visibility = request.queryParameters.getAll("visibility").orEmpty().map(::timelineVisibility),
        includeDeleted = request.queryParameters["includeDeleted"]?.toBooleanStrictOrNull() ?: false,
    )

private fun timelineProvenance(value: String): IncidentTimelineProvenance =
    IncidentTimelineProvenance.entries.firstOrNull { it.wire == value.trim().uppercase() }
        ?: throw IllegalArgumentException("Invalid incident timeline provenance")

private fun timelineVisibility(value: String): IncidentTimelineVisibility =
    IncidentTimelineVisibility.entries.firstOrNull { it.wire == value.trim().uppercase() }
        ?: throw IllegalArgumentException("Invalid incident timeline visibility")

private suspend fun io.ktor.server.application.ApplicationCall.respondTimelineFailure(
    action: suspend () -> Unit,
) {
    try {
        action()
    } catch (error: IncidentCommandException) {
        respondIncidentCommandFailure(error)
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
    }
}
