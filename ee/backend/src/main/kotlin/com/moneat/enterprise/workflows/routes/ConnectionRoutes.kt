// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.routes

import com.moneat.enterprise.workflows.models.ConnectionGroupResponse
import com.moneat.enterprise.workflows.models.ConnectionResponse
import com.moneat.enterprise.workflows.models.CreateConnectionGroupRequest
import com.moneat.enterprise.workflows.models.CreateConnectionRequest
import com.moneat.enterprise.workflows.models.RotateConnectionRequest
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.WorkflowConnectionGroupSummary
import com.moneat.workflows.WorkflowConnectionSummary
import com.moneat.workflows.WorkflowConnectionVault
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

private const val INVALID_ID_MESSAGE = "Invalid connection ID"
private const val INVALID_GROUP_ID_MESSAGE = "Invalid connection group ID"
private const val NOT_FOUND_MESSAGE = "Connection not found"
private const val GROUP_NOT_FOUND_MESSAGE = "Connection group not found"
private const val FORBIDDEN_MESSAGE = "Insufficient permissions"
private const val INVALID_TOKEN_MESSAGE = "Invalid token"
private const val INVALID_CONNECTION_REQUEST = "Invalid connection request"

/**
 * Connection-vault management routes (Enterprise: workflows_advanced). Secrets are
 * accepted on create/rotate but never returned: responses only carry type, name,
 * identifier tags and the last four characters. Write operations require ADMIN.
 *
 * The enterprise classpath has no Koin DI, so the ADMIN gate reads the shared
 * membership table directly (mirroring the core `requireRole(... ADMIN)` semantics).
 */
fun Route.connectionRoutes(vault: WorkflowConnectionVault) {
    route("/v1/workflows/connections") {
        authenticate("auth-jwt") {
            get {
                val organizationId = currentOrganizationId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                call.respond(vault.listConnections(organizationId).map { it.toResponse() })
            }

            post {
                val organizationId = currentOrganizationId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                val userId = requireConnectionAdmin(organizationId) ?: return@post
                val request = call.receive<CreateConnectionRequest>()
                suspendRunCatching {
                    vault.createConnection(
                        organizationId = organizationId,
                        type = request.type,
                        name = request.name,
                        identifierTags = request.identifierTags,
                        secret = request.secret,
                        createdBy = userId
                    )
                }.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it.toResponse()) },
                    onFailure = { respondConnectionError(it) }
                )
            }

            get("/{connectionId}") {
                val organizationId = currentOrganizationId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                val connectionId = call.parameters["connectionId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_ID_MESSAGE))
                val connection = vault.getConnection(organizationId, connectionId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(NOT_FOUND_MESSAGE))
                call.respond(connection.toResponse())
            }

            put("/{connectionId}/rotate") {
                val organizationId = currentOrganizationId()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                requireConnectionAdmin(organizationId) ?: return@put
                val connectionId = call.parameters["connectionId"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_ID_MESSAGE))
                val request = call.receive<RotateConnectionRequest>()
                suspendRunCatching {
                    vault.rotateConnection(organizationId, connectionId, request.secret)
                }.fold(
                    onSuccess = { updated ->
                        if (updated == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse(NOT_FOUND_MESSAGE))
                        } else {
                            call.respond(updated.toResponse())
                        }
                    },
                    onFailure = { respondConnectionError(it) }
                )
            }

            delete("/{connectionId}") {
                val organizationId = currentOrganizationId()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                requireConnectionAdmin(organizationId) ?: return@delete
                val connectionId = call.parameters["connectionId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_ID_MESSAGE))
                if (vault.deleteConnection(organizationId, connectionId)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(NOT_FOUND_MESSAGE))
                }
            }
        }
    }

    route("/v1/workflows/connection-groups") {
        authenticate("auth-jwt") {
            get {
                val organizationId = currentOrganizationId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                call.respond(vault.listGroups(organizationId).map { it.toResponse() })
            }

            post {
                val organizationId = currentOrganizationId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                val userId = requireConnectionAdmin(organizationId) ?: return@post
                val request = call.receive<CreateConnectionGroupRequest>()
                suspendRunCatching {
                    vault.createGroup(
                        organizationId = organizationId,
                        name = request.name,
                        connectionType = request.connectionType,
                        memberConnectionIds = request.memberConnectionIds,
                        selectionStrategy = request.selectionStrategy,
                        createdBy = userId
                    )
                }.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it.toResponse()) },
                    onFailure = { respondConnectionError(it) }
                )
            }

            delete("/{groupId}") {
                val organizationId = currentOrganizationId()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
                requireConnectionAdmin(organizationId) ?: return@delete
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_GROUP_ID_MESSAGE))
                if (vault.deleteGroup(organizationId, groupId)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(GROUP_NOT_FOUND_MESSAGE))
                }
            }
        }
    }
}

private fun WorkflowConnectionSummary.toResponse(): ConnectionResponse =
    ConnectionResponse(
        id = id,
        type = type,
        name = name,
        identifierTags = identifierTags,
        lastFour = lastFour,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

private fun WorkflowConnectionGroupSummary.toResponse(): ConnectionGroupResponse =
    ConnectionGroupResponse(
        id = id,
        name = name,
        connectionType = connectionType,
        memberConnectionIds = memberConnectionIds,
        selectionStrategy = selectionStrategy,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

private suspend fun RoutingContext.respondConnectionError(error: Throwable) {
    if (error is IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: INVALID_CONNECTION_REQUEST))
    } else {
        throw error
    }
}

/**
 * Resolve the caller and require at least ADMIN in the organization. Responds 403 and
 * returns null when the caller is missing or under-privileged; otherwise returns the
 * caller's user id (used for createdBy attribution).
 */
private suspend fun RoutingContext.requireConnectionAdmin(organizationId: Int): Int? {
    val userId = currentUserId()
    val role = userId?.let { uid ->
        transaction {
            Memberships
                .selectAll()
                .where { (Memberships.organization_id eq organizationId) and (Memberships.user_id eq uid) }
                .firstOrNull()
                ?.get(Memberships.role)
        }
    }
    val authorized = role != null && OrgRole.fromString(role).level >= OrgRole.ADMIN.level
    if (!authorized) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
        return null
    }
    return userId
}

private fun RoutingContext.currentOrganizationId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("orgId")?.asInt()

private fun RoutingContext.currentUserId(): Int? =
    call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
