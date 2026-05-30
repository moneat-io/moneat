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
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.ManualWorkflowRunRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowPreviewRequest
import com.moneat.workflows.models.WorkflowRunInstanceRequest
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
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
private const val INSTANCE_ID_ROUTE = "{instanceId}"
private const val WEBHOOK_SIGNATURE_HEADER = "X-Moneat-Workflow-Signature"
private const val WEBHOOK_EVENT_ID_HEADER = "X-Moneat-Webhook-Event"
private const val INVALID_WORKFLOW_ID_MESSAGE = "Invalid workflow ID"
private const val INVALID_INSTANCE_ID_MESSAGE = "Invalid workflow instance ID"
private const val WORKFLOW_NOT_FOUND_MESSAGE = "Workflow not found"
private const val WORKFLOW_INSTANCE_NOT_FOUND_MESSAGE = "Workflow instance not found"
private const val FORBIDDEN_MESSAGE = "Insufficient permissions"
private const val INVALID_WEBHOOK_SIGNATURE_MESSAGE = "Invalid workflow webhook signature"

fun Route.workflowRoutes() {
    val koin = GlobalContext.get()
    val workflowService = koin.get<WorkflowService>()
    val governanceService = koin.get<WorkflowGovernanceService>()
    val membershipService = koin.get<OrgMembershipService>()

    route("/v1/workflows") {
        webhookIngressRoute(workflowService)

        authenticate("auth-jwt") {
            catalogAndListRoutes(workflowService)
            workflowCrudRoutes(workflowService, membershipService)
            workflowRunQueryRoutes(workflowService)
            workflowLifecycleRoutes(workflowService, membershipService)
            workflowRunTriggerRoutes(workflowService, membershipService)
            workflowGovernanceRoutes(governanceService, membershipService)
        }
    }
}

private fun Route.webhookIngressRoute(workflowService: WorkflowService) {
    post("$WORKFLOW_ID_ROUTE/webhook") {
        val workflowId = workflowIdFromPath() ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val payload = call.receiveText()
        val signature = call.request.headers[WEBHOOK_SIGNATURE_HEADER]
        if (!workflowService.verifyWebhookSignature(workflowId, payload, signature)) {
            return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_WEBHOOK_SIGNATURE_MESSAGE))
        }
        val run = workflowService.createWebhookRun(
            workflowId = workflowId,
            payload = payload,
            eventId = call.request.headers[WEBHOOK_EVENT_ID_HEADER]
        ) ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
        call.respond(HttpStatusCode.Accepted, run)
    }
}

private fun Route.catalogAndListRoutes(workflowService: WorkflowService) {
    get("/catalog") {
        call.respond(workflowService.catalog())
    }

    get {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(workflowService.listWorkflows(organizationId))
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
}

private fun Route.workflowCrudRoutes(
    workflowService: WorkflowService,
    membershipService: OrgMembershipService
) {
    post {
        val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@post
        val request = call.receive<CreateWorkflowRequest>()
        suspendRunCatching {
            workflowService.createWorkflow(organizationId, request)
        }.fold(
            onSuccess = { workflow -> call.respond(HttpStatusCode.Created, workflow) },
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
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@put
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
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@delete
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
}

private fun Route.workflowRunQueryRoutes(workflowService: WorkflowService) {
    get("$WORKFLOW_ID_ROUTE/runs") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        val workflowId = workflowIdFromPath() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        call.respond(workflowService.listRuns(organizationId, workflowId, runLimit()))
    }

    get("$WORKFLOW_ID_ROUTE/instances") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        val workflowId = workflowIdFromPath() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        call.respond(workflowService.listRuns(organizationId, workflowId, runLimit()))
    }

    get("$WORKFLOW_ID_ROUTE/instances/$INSTANCE_ID_ROUTE") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        val workflowId = workflowIdFromPath() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val instanceId = instanceIdFromPath() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_INSTANCE_ID_MESSAGE)
        )
        val run = workflowService.getRun(organizationId, workflowId, instanceId)
            ?: return@get call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(WORKFLOW_INSTANCE_NOT_FOUND_MESSAGE)
            )
        call.respond(run)
    }
}

private fun Route.workflowLifecycleRoutes(
    workflowService: WorkflowService,
    membershipService: OrgMembershipService
) {
    post("$WORKFLOW_ID_ROUTE/publish") {
        val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@post
        val workflowId = workflowIdFromPath() ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val workflow = workflowService.publishWorkflow(organizationId, workflowId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
        call.respond(workflow)
    }

    post("$WORKFLOW_ID_ROUTE/unpublish") {
        val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@post
        val workflowId = workflowIdFromPath() ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val workflow = workflowService.unpublishWorkflow(organizationId, workflowId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
        call.respond(workflow)
    }

    get("$WORKFLOW_ID_ROUTE/webhook-signing") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@get
        val workflowId = workflowIdFromPath() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val signing = workflowService.webhookSigningInfo(organizationId, workflowId)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
        call.respond(signing)
    }
}

private fun Route.workflowRunTriggerRoutes(
    workflowService: WorkflowService,
    membershipService: OrgMembershipService
) {
    put("$WORKFLOW_ID_ROUTE/instances/$INSTANCE_ID_ROUTE/cancel") {
        val organizationId = currentOrganizationId() ?: return@put call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@put
        val workflowId = workflowIdFromPath() ?: return@put call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val instanceId = instanceIdFromPath() ?: return@put call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_INSTANCE_ID_MESSAGE)
        )
        val canceled = workflowService.cancelRun(organizationId, workflowId, instanceId)
            ?: return@put call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(WORKFLOW_INSTANCE_NOT_FOUND_MESSAGE)
            )
        call.respond(canceled)
    }

    post("$WORKFLOW_ID_ROUTE/run") {
        val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@post
        val workflowId = workflowIdFromPath() ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val request = call.receive<ManualWorkflowRunRequest>()
        val run = workflowService.runWorkflow(organizationId, workflowId, request, currentUserId())
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
        call.respond(HttpStatusCode.Accepted, run)
    }

    post("$WORKFLOW_ID_ROUTE/instances") {
        val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAdmin(membershipService, organizationId) ?: return@post
        val userId = currentUserId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        val workflowId = workflowIdFromPath() ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(INVALID_WORKFLOW_ID_MESSAGE)
        )
        val request = call.receive<WorkflowRunInstanceRequest>()
        suspendRunCatching {
            workflowService.createWorkflowInstance(organizationId, workflowId, request, userId)
        }.fold(
            onSuccess = { run ->
                if (run == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_NOT_FOUND_MESSAGE))
                } else {
                    call.respond(HttpStatusCode.Accepted, run)
                }
            },
            onFailure = { error -> respondWorkflowError(error) }
        )
    }
}

private fun RoutingContext.runLimit(): Int =
    call.request.queryParameters["limit"]
        ?.toIntOrNull()
        ?.coerceIn(MIN_WORKFLOW_RUN_LIMIT, MAX_WORKFLOW_RUN_LIMIT)
        ?: DEFAULT_WORKFLOW_RUN_LIMIT

internal suspend fun RoutingContext.respondWorkflowError(error: Throwable) {
    if (error is IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid workflow request"))
    } else {
        throw error
    }
}

internal suspend fun RoutingContext.ensureWorkflowAdmin(
    membershipService: OrgMembershipService,
    organizationId: Int
): Boolean? {
    val userId = currentUserId() ?: run {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }
    return suspendRunCatching {
        membershipService.requireRole(organizationId, userId, OrgRole.ADMIN)
        true
    }.getOrElse {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        null
    }
}

internal fun RoutingContext.workflowIdFromPath(): Int? =
    call.parameters["workflowId"]?.toIntOrNull()

private fun RoutingContext.instanceIdFromPath(): Int? =
    call.parameters["instanceId"]?.toIntOrNull()

internal fun RoutingContext.currentOrganizationId(): Int? {
    val principal = call.principal<JWTPrincipal>() ?: return null
    return principal.payload.getClaim("orgId").asInt()
}

internal fun RoutingContext.currentUserId(): Int? {
    val principal = call.principal<JWTPrincipal>() ?: return null
    return principal.payload.getClaim("userId").asInt()
}

internal const val WORKFLOW_GOVERNANCE_INVALID_ID_MESSAGE = INVALID_WORKFLOW_ID_MESSAGE
internal const val WORKFLOW_GOVERNANCE_NOT_FOUND_MESSAGE = WORKFLOW_NOT_FOUND_MESSAGE
internal const val WORKFLOW_GOVERNANCE_ID_ROUTE = WORKFLOW_ID_ROUTE
