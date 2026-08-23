// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.routes

import com.moneat.enterprise.alertroutes.commands.AlertGroupActor
import com.moneat.enterprise.alertroutes.commands.AttachAlertGroupIncidentCommand
import com.moneat.enterprise.alertroutes.commands.CreateAlertGroupTriageCommand
import com.moneat.enterprise.alertroutes.commands.MarkAlertGroupEpisodeUnrelatedCommand
import com.moneat.enterprise.alertroutes.commands.RemoveAlertGroupEpisodeCommand
import com.moneat.enterprise.alertroutes.services.AlertGroupCommandService
import com.moneat.enterprise.alertroutes.services.AlertGroupService
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.oncall.routes.OnCallUserContext
import com.moneat.enterprise.oncall.routes.incidentCommandKey
import com.moneat.enterprise.oncall.routes.requireUserContext
import com.moneat.enterprise.oncall.routes.respondIncidentCommandFailure
import com.moneat.shared.services.toUuidOrNull
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
import io.ktor.server.routing.route
import kotlin.uuid.Uuid

private const val ALERT_GROUP_BASE_PATH = "/v1/on-call/alert-groups"
private const val DEFAULT_ALERT_GROUP_LIMIT = 50

fun Route.alertGroupRoutes(
    groupService: AlertGroupService = AlertGroupService(),
    commandService: AlertGroupCommandService = AlertGroupCommandService(),
) {
    route(ALERT_GROUP_BASE_PATH) {
        authenticate("auth-jwt") {
            get {
                val context = call.requireUserContext() ?: return@get
                call.respondAlertGroupFailure {
                    call.respond(
                        groupService.list(
                            context.organizationId,
                            call.alertGroupLimit(),
                            call.alertGroupOffset(),
                        ).map { it.toResponse() },
                    )
                }
            }
            get("/{groupId}") {
                val context = call.requireUserContext() ?: return@get
                call.respondAlertGroupFailure {
                    call.respond(groupService.get(context.organizationId, call.groupId()).toResponse())
                }
            }
            post("/{groupId}/episodes/{episodeId}/unrelated") {
                val context = call.requireUserContext() ?: return@post
                val request = call.receive<AlertGroupExpectedVersionRequest>()
                call.respondAlertGroupFailure {
                    val result = commandService.execute(
                        MarkAlertGroupEpisodeUnrelatedCommand(
                            call.incidentCommandKey("alert-group-unrelated"),
                            context.toGroupActor(),
                            call.groupId(),
                            request.expectedVersion,
                            call.episodeId(),
                        ),
                    )
                    call.respond(result.group.toResponse())
                }
            }
            delete("/{groupId}/episodes/{episodeId}") {
                val context = call.requireUserContext() ?: return@delete
                call.respondAlertGroupFailure {
                    val result = commandService.execute(
                        RemoveAlertGroupEpisodeCommand(
                            call.incidentCommandKey("alert-group-remove"),
                            context.toGroupActor(),
                            call.groupId(),
                            call.expectedVersion(),
                            call.episodeId(),
                        ),
                    )
                    call.respond(result.group.toResponse())
                }
            }
            post("/{groupId}/attach") {
                val context = call.requireUserContext() ?: return@post
                val request = call.receive<AttachAlertGroupRequest>()
                call.respondAlertGroupFailure {
                    val incidentId = request.incidentId.toUuidOrNull()
                        ?: throw IllegalArgumentException("Invalid incident id")
                    val result = commandService.execute(
                        AttachAlertGroupIncidentCommand(
                            call.incidentCommandKey("alert-group-attach"),
                            context.toGroupActor(),
                            call.groupId(),
                            request.expectedVersion,
                            incidentId,
                        ),
                    )
                    call.respond(result.group.toResponse())
                }
            }
            post("/{groupId}/create-triage") {
                val context = call.requireUserContext() ?: return@post
                val request = call.receive<CreateAlertGroupTriageRequest>()
                call.respondAlertGroupFailure {
                    val result = commandService.execute(
                        CreateAlertGroupTriageCommand(
                            call.incidentCommandKey("alert-group-create-triage"),
                            context.toGroupActor(),
                            call.groupId(),
                            request.expectedVersion,
                            request.title,
                            request.summary,
                        ),
                    )
                    call.respond(HttpStatusCode.Created, result.group.toResponse())
                }
            }
        }
    }
}

private fun ApplicationCall.groupId(): Uuid =
    parameters["groupId"]?.toUuidOrNull() ?: throw IllegalArgumentException("Invalid alert group id")

private fun ApplicationCall.episodeId(): Uuid =
    parameters["episodeId"]?.toUuidOrNull() ?: throw IllegalArgumentException("Invalid alert episode id")

private fun ApplicationCall.expectedVersion(): Int =
    request.queryParameters["expectedVersion"]?.toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("Expected version is required")

private fun ApplicationCall.alertGroupLimit(): Int =
    request.queryParameters["limit"]?.toIntOrNull()
        ?: if (request.queryParameters["limit"] == null) {
            DEFAULT_ALERT_GROUP_LIMIT
        } else {
            throw IllegalArgumentException("Invalid alert group limit")
        }

private fun ApplicationCall.alertGroupOffset(): Long =
    request.queryParameters["offset"]?.toLongOrNull()
        ?: if (request.queryParameters["offset"] == null) {
            0
        } else {
            throw IllegalArgumentException("Invalid alert group offset")
        }

private fun OnCallUserContext.toGroupActor() = AlertGroupActor(organizationId, userId, "REST")

private suspend fun ApplicationCall.respondAlertGroupFailure(action: suspend () -> Unit) {
    try {
        action()
    } catch (error: IncidentCommandException) {
        respondIncidentCommandFailure(error)
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
    }
}
