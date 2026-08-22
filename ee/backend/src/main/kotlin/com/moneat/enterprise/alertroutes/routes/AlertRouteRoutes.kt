// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.routes

import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.DeleteAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.ReorderAlertRoutesCommand
import com.moneat.enterprise.alertroutes.commands.UpdateAlertRouteCommand
import com.moneat.enterprise.alertroutes.services.AlertRouteCommandService
import com.moneat.enterprise.alertroutes.services.AlertRouteEvaluationService
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.oncall.routes.OnCallUserContext
import com.moneat.enterprise.oncall.routes.incidentCommandKey
import com.moneat.enterprise.oncall.routes.requireIncidentAdminContext
import com.moneat.enterprise.oncall.routes.requireUserContext
import com.moneat.enterprise.oncall.routes.respondIncidentCommandFailure
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
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

private const val ALERT_ROUTE_BASE_PATH = "/v1/on-call/alert-routes"

/**
 * Administrator APIs for enterprise Alert Routes.
 *
 * These routes are distinct from the open-source incident-provider routing rules: they configure
 * native paging, grouping, incident creation, and recovery, and never change provider forwarding.
 */
fun Route.alertRouteRoutes(
    commandService: AlertRouteCommandService = AlertRouteCommandService(),
    evaluationService: AlertRouteEvaluationService = AlertRouteEvaluationService(),
) {
    route(ALERT_ROUTE_BASE_PATH) {
        authenticate("auth-jwt") {
            registerCollectionRoutes(commandService)
            registerPreviewRoute(evaluationService)
            registerReorderRoute(commandService)
            registerItemRoutes(commandService)
        }
    }
}

private fun Route.registerCollectionRoutes(commandService: AlertRouteCommandService) {
    get {
        val context = call.requireUserContext() ?: return@get
        call.respondAlertRouteFailure {
            call.respond(commandService.list(context.organizationId).map { it.toResponse() })
        }
    }
    post {
        val context = call.requireIncidentAdminContext() ?: return@post
        val request = call.receive<AlertRouteRequest>()
        call.respondAlertRouteFailure {
            val result =
                commandService.execute(
                    CreateAlertRouteCommand(
                        commandKey = call.incidentCommandKey("alert-route-create"),
                        actor = context.toActor(),
                        specification = request.toSpecification(),
                    ),
                )
            val route = result.route ?: throw IncidentCommandNotFoundException("Alert route not found")
            call.respond(if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Created, route.toResponse())
        }
    }
}

private fun Route.registerPreviewRoute(evaluationService: AlertRouteEvaluationService) {
    post("/preview") {
        val context = call.requireUserContext() ?: return@post
        val request = call.receive<AlertRoutePreviewRequest>()
        call.respondAlertRouteFailure {
            val result =
                when {
                    request.episodeId != null ->
                        evaluationService.previewSavedEpisode(
                            organizationId = context.organizationId,
                            episodeId =
                                request.episodeId.toUuidOrNull()
                                    ?: throw IllegalArgumentException("Invalid alert episode id"),
                            metadata = request.metadata,
                        )
                    request.sample != null ->
                        evaluationService.preview(context.organizationId, request.sample.toInput())
                    else -> throw IllegalArgumentException("Preview requires an episode id or a sample alert")
                }
            call.respond(result.toPreviewResponse())
        }
    }
}

private fun Route.registerReorderRoute(commandService: AlertRouteCommandService) {
    post("/reorder") {
        val context = call.requireIncidentAdminContext() ?: return@post
        val request = call.receive<ReorderAlertRoutesRequest>()
        call.respondAlertRouteFailure {
            val result =
                commandService.execute(
                    ReorderAlertRoutesCommand(
                        commandKey = call.incidentCommandKey("alert-route-reorder"),
                        actor = context.toActor(),
                        orderedRoutes = request.routes.map { it.toInput() },
                    ),
                )
            call.respond(result.routes.map { it.toResponse() })
        }
    }
}

private fun Route.registerItemRoutes(commandService: AlertRouteCommandService) {
    get("/{routeId}") {
        val context = call.requireUserContext() ?: return@get
        call.respondAlertRouteFailure {
            val routeId = parseRouteId(call.parameters["routeId"])
            call.respond(commandService.get(context.organizationId, routeId).toResponse())
        }
    }
    get("/{routeId}/revisions") {
        val context = call.requireUserContext() ?: return@get
        call.respondAlertRouteFailure {
            val routeId = parseRouteId(call.parameters["routeId"])
            call.respond(commandService.revisions(context.organizationId, routeId).map { it.toPayload() })
        }
    }
    put("/{routeId}") {
        val context = call.requireIncidentAdminContext() ?: return@put
        val request = call.receive<AlertRouteRequest>()
        call.respondAlertRouteFailure {
            val result =
                commandService.execute(
                    UpdateAlertRouteCommand(
                        commandKey = call.incidentCommandKey("alert-route-update"),
                        actor = context.toActor(),
                        routeId = parseRouteId(call.parameters["routeId"]),
                        expectedRevision =
                            requireNotNull(request.expectedRevision) { "Expected revision is required" },
                        specification = request.toSpecification(),
                    ),
                )
            val route = result.route ?: throw IncidentCommandNotFoundException("Alert route not found")
            call.respond(route.toResponse())
        }
    }
    delete("/{routeId}") {
        val context = call.requireIncidentAdminContext() ?: return@delete
        call.respondAlertRouteFailure {
            commandService.execute(
                DeleteAlertRouteCommand(
                    commandKey = call.incidentCommandKey("alert-route-delete"),
                    actor = context.toActor(),
                    routeId = parseRouteId(call.parameters["routeId"]),
                    expectedRevision = call.expectedRevision(),
                ),
            )
            call.respond(HttpStatusCode.OK, MessageResponse("Alert route deleted"))
        }
    }
}

private fun ApplicationCall.expectedRevision(): Int {
    val raw = requireNotNull(request.queryParameters["expectedRevision"]) { "Expected revision is required" }
    return raw.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid expected revision: $raw")
}

private fun OnCallUserContext.toActor(): AlertRouteActor =
    AlertRouteActor(organizationId = organizationId, userId = userId, origin = "REST")

private suspend fun ApplicationCall.respondAlertRouteFailure(action: suspend () -> Unit) {
    try {
        action()
    } catch (error: IncidentCommandException) {
        respondIncidentCommandFailure(error)
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
    }
}
