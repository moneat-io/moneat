// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.enterprise.rbac.models.AssignRoleRequest
import com.moneat.enterprise.rbac.models.CreateRoleRequest
import com.moneat.enterprise.rbac.models.UpdateRoleRequest
import com.moneat.enterprise.rbac.services.RbacService
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val INVALID_ROLE_ID_MESSAGE = "Invalid role ID"
private const val INVALID_USER_ID_MESSAGE = "Invalid user ID"
private const val ROLE_NOT_FOUND_MESSAGE = "Role not found"
private const val ASSIGNMENT_NOT_FOUND_MESSAGE = "Role assignment not found"
private const val FORBIDDEN_MESSAGE = "Insufficient permissions"
private const val INVALID_TOKEN_MESSAGE = "Invalid token"
private const val INVALID_RBAC_REQUEST = "Invalid RBAC request"

/**
 * Cross-cutting RBAC management routes (Enterprise: advanced_rbac). Roles carry namespaced
 * permission keys and may be assigned to users; both are org-scoped. Reads require
 * membership; writes require ADMIN.
 *
 * The enterprise classpath has no Koin DI, so the ADMIN gate reads the shared membership
 * table directly (mirroring the core `requireRole(... ADMIN)` semantics).
 */
fun Route.rbacRoutes(service: RbacService) {
    route("/v1/rbac/roles") {
        authenticate("auth-jwt") {
            get {
                val member = requireMember() ?: return@get
                call.respond(service.listRoles(member.organizationId))
            }

            post {
                val member = requireMember() ?: return@post
                val userId = requireRbacAdmin(member) ?: return@post
                val request = call.receive<CreateRoleRequest>()
                suspendRunCatching {
                    service.createRole(member.organizationId, request.name, request.permissions, userId)
                }.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it) },
                    onFailure = { respondRbacError(it) }
                )
            }

            get("/{roleId}") {
                val member = requireMember() ?: return@get
                val roleId = roleIdParam() ?: return@get
                val role = service.getRole(member.organizationId, roleId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(ROLE_NOT_FOUND_MESSAGE))
                call.respond(role)
            }

            put("/{roleId}") {
                val member = requireMember() ?: return@put
                requireRbacAdmin(member) ?: return@put
                val roleId = roleIdParam() ?: return@put
                val request = call.receive<UpdateRoleRequest>()
                suspendRunCatching {
                    service.updateRole(member.organizationId, roleId, request.name, request.permissions)
                }.fold(
                    onSuccess = { updated ->
                        if (updated == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse(ROLE_NOT_FOUND_MESSAGE))
                        } else {
                            call.respond(updated)
                        }
                    },
                    onFailure = { respondRbacError(it) }
                )
            }

            delete("/{roleId}") {
                val member = requireMember() ?: return@delete
                requireRbacAdmin(member) ?: return@delete
                val roleId = roleIdParam() ?: return@delete
                if (service.deleteRole(member.organizationId, roleId)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ROLE_NOT_FOUND_MESSAGE))
                }
            }

            get("/{roleId}/assignments") {
                val member = requireMember() ?: return@get
                val roleId = roleIdParam() ?: return@get
                call.respond(service.listAssignments(member.organizationId, roleId))
            }

            post("/{roleId}/assignments") {
                val member = requireMember() ?: return@post
                requireRbacAdmin(member) ?: return@post
                val roleId = roleIdParam() ?: return@post
                val request = call.receive<AssignRoleRequest>()
                val assignment = service.assignRole(member.organizationId, roleId, request.userId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(ROLE_NOT_FOUND_MESSAGE))
                call.respond(HttpStatusCode.Created, assignment)
            }

            delete("/{roleId}/assignments/{userId}") {
                val member = requireMember() ?: return@delete
                requireRbacAdmin(member) ?: return@delete
                val roleId = roleIdParam() ?: return@delete
                val userId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_USER_ID_MESSAGE))
                if (service.unassignRole(member.organizationId, roleId, userId)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ASSIGNMENT_NOT_FOUND_MESSAGE))
                }
            }
        }
    }
}

private data class RbacMember(
    val organizationId: Int,
    val userId: Int,
    val role: OrgRole
)

private suspend fun RoutingContext.roleIdParam(): Int? {
    val roleId = call.parameters["roleId"]?.toIntOrNull()
    if (roleId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_ROLE_ID_MESSAGE))
        return null
    }
    return roleId
}

private suspend fun RoutingContext.respondRbacError(error: Throwable) {
    if (error is IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: INVALID_RBAC_REQUEST))
    } else {
        throw error
    }
}

/** Resolve the caller's current organization membership from a matching membership row. */
private suspend fun RoutingContext.requireMember(): RbacMember? {
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
    return RbacMember(organizationId, userId, OrgRole.fromString(role))
}

/**
 * Write operations require ADMIN. Responds 403 and returns null when the member is
 * under-privileged; otherwise returns the caller's user id for createdBy attribution.
 */
private suspend fun RoutingContext.requireRbacAdmin(member: RbacMember): Int? {
    if (member.role.level < OrgRole.ADMIN.level) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }
    return member.userId
}

private fun RoutingContext.currentOrganizationId(): Int? =
    call.principal<JWTPrincipal>()?.currentOrgIdOrNull()

private fun RoutingContext.currentUserId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
