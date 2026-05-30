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

import com.moneat.org.services.OrgMembershipService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.models.InstantiateBlueprintRequest
import com.moneat.workflows.models.WorkflowImportRequest
import com.moneat.workflows.services.WorkflowBlueprintCatalog
import com.moneat.workflows.services.WorkflowGovernanceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private const val BLUEPRINT_NOT_FOUND_MESSAGE = "Workflow blueprint not found"
private const val DEFAULT_AUDIT_LIMIT = 100
private const val MIN_AUDIT_LIMIT = 1
private const val MAX_AUDIT_LIMIT = 500

internal fun Route.workflowGovernanceRoutes(
    governanceService: WorkflowGovernanceService,
    membershipService: OrgMembershipService
) {
    blueprintRoutes(governanceService, membershipService)
    overviewAndUsageRoutes(governanceService)
    auditRoutes(governanceService)
    exportImportRoutes(governanceService, membershipService)
}

private fun Route.blueprintRoutes(
    governanceService: WorkflowGovernanceService,
    membershipService: OrgMembershipService
) {
    get("/blueprints") {
        currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(WorkflowBlueprintCatalog.list())
    }

    get("/blueprints/{key}") {
        currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        val key = call.parameters["key"].orEmpty()
        val blueprint = WorkflowBlueprintCatalog.get(key)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(BLUEPRINT_NOT_FOUND_MESSAGE))
        call.respond(WorkflowBlueprintCatalog.detail(blueprint))
    }

    post("/blueprints/{key}/instantiate") {
        val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAccess(membershipService, organizationId) ?: return@post
        val key = call.parameters["key"].orEmpty()
        val request = call.receive<InstantiateBlueprintRequest>()
        suspendRunCatching {
            governanceService.instantiateBlueprint(organizationId, key, request.name, currentUserId())
        }.fold(
            onSuccess = { workflow -> call.respond(HttpStatusCode.Created, workflow) },
            onFailure = { error -> respondWorkflowError(error) }
        )
    }
}

private fun Route.overviewAndUsageRoutes(governanceService: WorkflowGovernanceService) {
    get("/overview") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(governanceService.overview(organizationId))
    }

    get("/usage") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(governanceService.usage(organizationId))
    }
}

private fun Route.auditRoutes(governanceService: WorkflowGovernanceService) {
    get("/audit") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(governanceService.listAudit(organizationId, workflowId = null, limit = auditLimit()))
    }

    get("$WORKFLOW_GOVERNANCE_ID_ROUTE/audit") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        val workflowId = workflowIdFromPath() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(WORKFLOW_GOVERNANCE_INVALID_ID_MESSAGE)
        )
        call.respond(governanceService.listAudit(organizationId, workflowId, auditLimit()))
    }
}

private fun Route.exportImportRoutes(
    governanceService: WorkflowGovernanceService,
    membershipService: OrgMembershipService
) {
    get("$WORKFLOW_GOVERNANCE_ID_ROUTE/export") {
        val organizationId = currentOrganizationId() ?: return@get call.respond(HttpStatusCode.Forbidden)
        val workflowId = workflowIdFromPath() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(WORKFLOW_GOVERNANCE_INVALID_ID_MESSAGE)
        )
        val export = governanceService.export(organizationId, workflowId, currentUserId())
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(WORKFLOW_GOVERNANCE_NOT_FOUND_MESSAGE))
        call.respond(export)
    }

    post("/import") {
        val organizationId = currentOrganizationId() ?: return@post call.respond(HttpStatusCode.Forbidden)
        ensureWorkflowAccess(membershipService, organizationId) ?: return@post
        val request = call.receive<WorkflowImportRequest>()
        suspendRunCatching {
            governanceService.import(organizationId, request, currentUserId())
        }.fold(
            onSuccess = { workflow -> call.respond(workflow) },
            onFailure = { error -> respondWorkflowError(error) }
        )
    }
}

private fun io.ktor.server.routing.RoutingContext.auditLimit(): Int =
    call.request.queryParameters["limit"]
        ?.toIntOrNull()
        ?.coerceIn(MIN_AUDIT_LIMIT, MAX_AUDIT_LIMIT)
        ?: DEFAULT_AUDIT_LIMIT
