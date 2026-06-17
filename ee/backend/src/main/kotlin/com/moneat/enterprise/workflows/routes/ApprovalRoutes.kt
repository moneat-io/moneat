// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.enterprise.workflows.models.RespondApprovalRequest
import com.moneat.enterprise.workflows.models.WorkflowApprovals
import com.moneat.enterprise.workflows.services.WorkflowApprovalService
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val INVALID_APPROVAL_ID_MESSAGE = "Invalid approval ID"
private const val APPROVAL_NOT_FOUND_MESSAGE = "Approval not found"
private const val FORBIDDEN_MESSAGE = "Insufficient permissions"
private const val INVALID_TOKEN_MESSAGE = "Invalid token"

fun Route.approvalRoutes(approvalService: WorkflowApprovalService) {
    route("/v1/workflows/approvals") {
        authenticate("auth-jwt") {
            get {
                val member = requireApprovalMember() ?: return@get
                call.respond(approvalService.listPending(member.organizationId))
            }

            post("/{approvalId}/respond") {
                val member = requireApprovalMember() ?: return@post
                requireApprovalAdmin(member) ?: return@post
                val approvalId = approvalIdParam(member.organizationId) ?: return@post
                val request = call.receive<RespondApprovalRequest>()
                val response =
                    approvalService.respond(
                        organizationId = member.organizationId,
                        approvalId = approvalId,
                        approved = request.approved,
                        actorUserId = member.userId,
                        comment = request.comment
                    ) ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(APPROVAL_NOT_FOUND_MESSAGE)
                    )
                call.respond(response)
            }
        }
    }
}

private data class WorkflowApprovalMember(
    val organizationId: Int,
    val userId: Int,
    val role: OrgRole
)

private suspend fun RoutingContext.requireApprovalMember(): WorkflowApprovalMember? {
    val organizationId = currentOrganizationId()
    val userId = currentUserId()
    if (organizationId == null || userId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
        return null
    }
    val role =
        transaction {
            Memberships
                .selectAll()
                .where { (Memberships.organization_id eq organizationId) and (Memberships.user_id eq userId) }
                .firstOrNull()
                ?.get(Memberships.role)
        } ?: run {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
            return null
        }
    return WorkflowApprovalMember(organizationId, userId, OrgRole.fromString(role))
}

private suspend fun RoutingContext.requireApprovalAdmin(member: WorkflowApprovalMember): Int? {
    if (member.role.level < OrgRole.ADMIN.level) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }
    return member.userId
}

private suspend fun RoutingContext.approvalIdParam(organizationId: Int): Int? {
    val resourceId = call.parameters["approvalId"]?.toUuidOrNull()
    if (resourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_APPROVAL_ID_MESSAGE))
        return null
    }
    val approvalId =
        transaction {
            WorkflowApprovals
                .selectAll()
                .where {
                    (WorkflowApprovals.organizationId eq organizationId) and
                        (WorkflowApprovals.resourceId eq resourceId)
                }
                .firstOrNull()
                ?.get(WorkflowApprovals.id)
                ?.value
        }
    if (approvalId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(APPROVAL_NOT_FOUND_MESSAGE))
    }
    return approvalId
}

private fun RoutingContext.currentOrganizationId(): Int? =
    call.principal<JWTPrincipal>()?.currentOrgIdOrNull()

private fun RoutingContext.currentUserId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
