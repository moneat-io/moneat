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

package com.moneat.workflows.routes

import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowPreviewRequest
import com.moneat.workflows.services.WorkflowService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

private const val MIN_WORKFLOW_RUN_LIMIT = 1
private const val MAX_WORKFLOW_RUN_LIMIT = 100
private const val DEFAULT_WORKFLOW_RUN_LIMIT = 50
private const val WORKFLOW_ID_ROUTE = "/{workflowId}"
private const val INVALID_WORKFLOW_ID_MESSAGE = "Invalid workflow ID"
private const val WORKFLOW_NOT_FOUND_MESSAGE = "Workflow not found"

fun Route.workflowRoutes() {
    val workflowService = GlobalContext.get().get<WorkflowService>()

    route("/v1/workflows") {
        authenticate("auth-jwt") {
            get("/catalog") {
                call.respond(workflowService.catalog())
            }

            get {
                val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(workflowService.listWorkflows(organizationId))
            }

            post {
                val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<CreateWorkflowRequest>()
                suspendRunCatching {
                    workflowService.createWorkflow(organizationId, request)
                }.fold(
                    onSuccess = { workflow -> call.respond(HttpStatusCode.Created, workflow) },
                    onFailure = { error -> respondWorkflowError(error) }
                )
            }

            post("/preview") {
                currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<WorkflowPreviewRequest>()
                suspendRunCatching {
                    workflowService.previewWorkflow(request)
                }.fold(
                    onSuccess = { preview -> call.respond(preview) },
                    onFailure = { error -> respondWorkflowError(error) }
                )
            }

            post("/test-message") {
                val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<WorkflowPreviewRequest>()
                suspendRunCatching {
                    workflowService.testWorkflowMessage(organizationId, request)
                }.fold(
                    onSuccess = { result -> call.respond(result) },
                    onFailure = { error -> respondWorkflowError(error) }
                )
            }

            get(WORKFLOW_ID_ROUTE) {
                val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
                val workflowId = workflowIdFromPath() ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
                )
                val workflow = workflowService.getWorkflow(organizationId, workflowId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
                call.respond(workflow)
            }

            put(WORKFLOW_ID_ROUTE) {
                val organizationId = currentOrganizationId() ?: return@put call.respond(HttpStatusCode.Forbidden)
                val workflowId = workflowIdFromPath() ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
                )
                val request = call.receive<UpdateWorkflowRequest>()
                suspendRunCatching {
                    workflowService.updateWorkflow(organizationId, workflowId, request)
                }.fold(
                    onSuccess = { workflow ->
                        if (workflow == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
                        } else {
                            call.respond(workflow)
                        }
                    },
                    onFailure = { error -> respondWorkflowError(error) }
                )
            }

            delete(WORKFLOW_ID_ROUTE) {
                val organizationId = currentOrganizationId() ?: return@delete call.respond(HttpStatusCode.Forbidden)
                val workflowId = workflowIdFromPath() ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
                )
                suspendRunCatching {
                    workflowService.deleteWorkflow(organizationId, workflowId)
                }.fold(
                    onSuccess = { deleted ->
                        if (deleted) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
                        }
                    },
                    onFailure = { error -> respondWorkflowError(error) }
                )
            }

            get("$WORKFLOW_ID_ROUTE/runs") {
                val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
                val workflowId = workflowIdFromPath() ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
                )
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(MIN_WORKFLOW_RUN_LIMIT, MAX_WORKFLOW_RUN_LIMIT)
                        ?: DEFAULT_WORKFLOW_RUN_LIMIT
                call.respond(workflowService.listRuns(organizationId, workflowId, limit))
            }
        }
    }
}

private suspend fun RoutingContext.respondWorkflowError(error: Throwable) {
    if (error is IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid workflow request"))
    } else {
        throw error
    }
}

private fun RoutingContext.workflowIdFromPath(): Int? =
    call.parameters["workflowId"]?.toIntOrNull()

private fun RoutingContext.currentOrganizationId(): Int? {
    val principal = call.principal<JWTPrincipal>() ?: return null
    return principal.payload.getClaim("orgId").asInt()
}
