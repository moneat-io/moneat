// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.incidents.commands.AssignIncidentRoleCommand
import com.moneat.enterprise.incidents.commands.ClaimIncidentRoleCommand
import com.moneat.enterprise.incidents.commands.HandoverIncidentRoleCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.commands.LeaveIncidentCommand
import com.moneat.enterprise.incidents.commands.SetIncidentParticipationCommand
import com.moneat.enterprise.incidents.commands.UnassignIncidentRoleCommand
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.responders.IncidentResponderService
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class AssignIncidentRoleRequest(
    val userId: String,
    val expectedVersion: Int? = null,
)

@Serializable
data class ClaimIncidentRoleRequest(
    val expectedVersion: Int? = null,
)

@Serializable
data class HandoverIncidentRoleRequest(
    val userId: String,
    val note: String? = null,
    val expectedVersion: Int? = null,
)

@Serializable
data class SetIncidentParticipantRequest(
    val userId: String? = null,
    val expectedVersion: Int? = null,
)

internal fun Route.registerIncidentResponderRoutes(
    incidentService: OnCallIncidentService,
    responderService: IncidentResponderService,
) {
    get("/{id}/roles") {
        val context = call.requireUserContext() ?: return@get
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@get
        call.respond(responderService.listAssignments(context.organizationId, incidentId))
    }
    post("/{id}/roles/{roleId}/assign") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        val request = call.receive<AssignIncidentRoleRequest>()
        call.respondResponderFailure {
            val roleId = responderService.resolveRoleId(context.organizationId, call.parameters["roleId"].orEmpty())
            val userId = responderService.resolveUserId(context.organizationId, request.userId)
            incidentService.executeIncidentCommand(
                AssignIncidentRoleCommand(
                    commandKey = call.incidentCommandKey("assign-role"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    roleDefinitionId = roleId,
                    assigneeUserId = userId,
                    expectedVersion = request.expectedVersion,
                ),
            )
            call.respond(responderService.listAssignments(context.organizationId, incidentId))
        }
    }
    post("/{id}/roles/{roleId}/claim") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        val request = call.receive<ClaimIncidentRoleRequest>()
        call.respondResponderFailure {
            val roleId = responderService.resolveRoleId(context.organizationId, call.parameters["roleId"].orEmpty())
            incidentService.executeIncidentCommand(
                ClaimIncidentRoleCommand(
                    commandKey = call.incidentCommandKey("claim-role"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    roleDefinitionId = roleId,
                    expectedVersion = request.expectedVersion,
                ),
            )
            call.respond(responderService.listAssignments(context.organizationId, incidentId))
        }
    }
    delete("/{id}/roles/{roleId}") {
        val context = call.requireUserContext() ?: return@delete
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@delete
        call.respondResponderFailure {
            val roleId = responderService.resolveRoleId(context.organizationId, call.parameters["roleId"].orEmpty())
            incidentService.executeIncidentCommand(
                UnassignIncidentRoleCommand(
                    commandKey = call.incidentCommandKey("unassign-role"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    roleDefinitionId = roleId,
                ),
            )
            call.respond(HttpStatusCode.OK, MessageResponse("Incident role unassigned"))
        }
    }
    post("/{id}/roles/{roleId}/handover") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        val request = call.receive<HandoverIncidentRoleRequest>()
        call.respondResponderFailure {
            val roleId = responderService.resolveRoleId(context.organizationId, call.parameters["roleId"].orEmpty())
            val userId = responderService.resolveUserId(context.organizationId, request.userId)
            incidentService.executeIncidentCommand(
                HandoverIncidentRoleCommand(
                    commandKey = call.incidentCommandKey("handover-role"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    roleDefinitionId = roleId,
                    toUserId = userId,
                    note = request.note,
                    expectedVersion = request.expectedVersion,
                ),
            )
            call.respond(responderService.listAssignments(context.organizationId, incidentId))
        }
    }
    get("/{id}/participants") {
        val context = call.requireUserContext() ?: return@get
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@get
        call.respond(responderService.listParticipants(context.organizationId, incidentId))
    }
    post("/{id}/participants") {
        call.setParticipation(incidentService, responderService, IncidentParticipationType.PARTICIPANT)
    }
    post("/{id}/observers") {
        call.setParticipation(incidentService, responderService, IncidentParticipationType.OBSERVER)
    }
    delete("/{id}/participants/{userId}") {
        val context = call.requireUserContext() ?: return@delete
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@delete
        call.respondResponderFailure {
            val userId = responderService.resolveUserId(context.organizationId, call.parameters["userId"].orEmpty())
            incidentService.executeIncidentCommand(
                LeaveIncidentCommand(
                    commandKey = call.incidentCommandKey("leave-incident"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    userId = userId,
                ),
            )
            call.respond(HttpStatusCode.OK, MessageResponse("Incident participant removed"))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.setParticipation(
    incidentService: OnCallIncidentService,
    responderService: IncidentResponderService,
    participationType: IncidentParticipationType,
) {
    val context = requireUserContext() ?: return
    val incidentId = requireIncidentId(context.organizationId) ?: return
    val request = receive<SetIncidentParticipantRequest>()
    respondResponderFailure {
        val userId = request.userId?.let { responderService.resolveUserId(context.organizationId, it) }
            ?: context.userId
        incidentService.executeIncidentCommand(
            SetIncidentParticipationCommand(
                commandKey = incidentCommandKey(participationType.wire.lowercase()),
                actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                incidentId = incidentId,
                userId = userId,
                participationType = participationType,
                expectedVersion = request.expectedVersion,
            ),
        )
        respond(responderService.listParticipants(context.organizationId, incidentId))
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondResponderFailure(
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
