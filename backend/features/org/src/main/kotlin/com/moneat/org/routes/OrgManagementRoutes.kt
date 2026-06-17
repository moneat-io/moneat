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

package com.moneat.org.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.events.models.AcceptInviteRequest
import com.moneat.events.models.BulkInviteRequest
import com.moneat.events.models.InviteMemberRequest
import com.moneat.events.models.OrgMembersResponse
import com.moneat.events.models.UpdateMemberRoleRequest
import com.moneat.billing.services.FeatureNotAvailableException
import com.moneat.org.models.CreateOrganizationTeamRequest
import com.moneat.org.models.UpdateOrganizationTeamRequest
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.org.services.OrganizationTeamService
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.BooleanResponse
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

private const val INVALID_TOKEN_ERROR = "Invalid token"
private const val INVALID_TEAM_ID_ERROR = "Invalid team ID"
private const val TEAMS_UNAVAILABLE_ERROR = "Teams is not available"

private fun parseOrgResourceId(value: String): Uuid? =
    value.toUuidOrNull()

private fun String?.validTeamIdOrNull(): String? =
    this?.takeIf { it.isNotBlank() && it.toUuidOrNull() != null }

private fun resolveTeamService(teamService: OrganizationTeamService?): OrganizationTeamService =
    teamService ?: GlobalContext.get().get()

private fun FeatureNotAvailableException.errorResponse(): ErrorResponse =
    ErrorResponse(message ?: TEAMS_UNAVAILABLE_ERROR)

private suspend fun ApplicationCall.respondTeamRouteFailure(error: Throwable) {
    when (error) {
        is FeatureNotAvailableException -> respond(HttpStatusCode.Forbidden, error.errorResponse())
        is NotFoundException -> respond(HttpStatusCode.NotFound, ErrorResponse(error.message ?: "Team not found"))
        is IllegalStateException ->
            respond(HttpStatusCode.Forbidden, ErrorResponse(error.message ?: "Insufficient permissions"))
        else -> throw error
    }
}

private fun Route.organizationTeamRoutes(teamService: OrganizationTeamService?) {
    listOrganizationTeamsRoute(teamService)
    createOrganizationTeamRoute(teamService)
    updateOrganizationTeamRoute(teamService)
    deleteOrganizationTeamRoute(teamService)
}

private fun Route.listOrganizationTeamsRoute(teamService: OrganizationTeamService?) {
    get("/teams") {
        val resolvedTeamService = resolveTeamService(teamService)
        val principal = call.principal<JWTPrincipal>()!!
        val userId = principal.payload.getClaim("userId").asInt()
        val orgId = principal.currentOrgIdOrNull() ?: return@get call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(INVALID_TOKEN_ERROR)
        )

        try {
            call.respond(resolvedTeamService.listTeams(orgId, userId))
        } catch (e: Exception) {
            call.respondTeamRouteFailure(e)
        }
    }
}

private fun Route.createOrganizationTeamRoute(teamService: OrganizationTeamService?) {
    post("/teams") {
        val resolvedTeamService = resolveTeamService(teamService)
        val principal = call.principal<JWTPrincipal>()!!
        val userId = principal.payload.getClaim("userId").asInt()
        val orgId = principal.currentOrgIdOrNull() ?: return@post call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(INVALID_TOKEN_ERROR)
        )
        val request = call.receive<CreateOrganizationTeamRequest>()

        try {
            val team = resolvedTeamService.createTeam(orgId, userId, request)
            call.respond(HttpStatusCode.Created, team)
        } catch (e: Exception) {
            call.respondTeamRouteFailure(e)
        }
    }
}

private fun Route.updateOrganizationTeamRoute(teamService: OrganizationTeamService?) {
    put("/teams/{teamId}") {
        val resolvedTeamService = resolveTeamService(teamService)
        val principal = call.principal<JWTPrincipal>()!!
        val userId = principal.payload.getClaim("userId").asInt()
        val orgId = principal.currentOrgIdOrNull() ?: return@put call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(INVALID_TOKEN_ERROR)
        )
        val teamId = call.parameters["teamId"].validTeamIdOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_TEAM_ID_ERROR))
        val request = call.receive<UpdateOrganizationTeamRequest>()

        try {
            call.respond(resolvedTeamService.updateTeam(orgId, userId, teamId, request))
        } catch (e: Exception) {
            call.respondTeamRouteFailure(e)
        }
    }
}

private fun Route.deleteOrganizationTeamRoute(teamService: OrganizationTeamService?) {
    delete("/teams/{teamId}") {
        val resolvedTeamService = resolveTeamService(teamService)
        val principal = call.principal<JWTPrincipal>()!!
        val userId = principal.payload.getClaim("userId").asInt()
        val orgId = principal.currentOrgIdOrNull() ?: return@delete call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(INVALID_TOKEN_ERROR)
        )
        val teamId = call.parameters["teamId"].validTeamIdOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_TEAM_ID_ERROR))

        try {
            val deleted = resolvedTeamService.deleteTeam(orgId, userId, teamId)
            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Team not found"))
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            call.respondTeamRouteFailure(e)
        }
    }
}

fun Route.orgManagementRoutes(
    membershipService: OrgMembershipService = GlobalContext.get().get(),
    invitationService: OrgInvitationService = GlobalContext.get().get(),
    teamService: OrganizationTeamService? = null,
) {
    route("/v1/org") {
        authenticate("auth-jwt") {
            organizationTeamRoutes(teamService)

            // Get all members and pending invitations
            get("/members") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.currentOrgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(INVALID_TOKEN_ERROR)
                )

                membershipService.requireRole(orgId, userId, OrgRole.MEMBER)

                val members = membershipService.getMembers(orgId)
                val pendingInvitations = invitationService.getPendingInvitations(orgId)

                call.respond(OrgMembersResponse(members, pendingInvitations))
            }

            // Update member role
            put("/members/{userId}/role") {
                val principal = call.principal<JWTPrincipal>()!!
                val requestingUserId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.currentOrgIdOrNull() ?: return@put call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(INVALID_TOKEN_ERROR)
                )
                val targetUserResourceId =
                    call.parameters["userId"]?.let(::parseOrgResourceId)
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                val targetUserId =
                    membershipService.resolveMemberUserId(orgId, targetUserResourceId)
                        ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Member not found"))

                val request = call.receive<UpdateMemberRoleRequest>()

                membershipService.updateMemberRole(orgId, targetUserId, request.role, requestingUserId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            }

            // Remove member
            delete("/members/{userId}") {
                val principal = call.principal<JWTPrincipal>()!!
                val requestingUserId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.currentOrgIdOrNull() ?: return@delete call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(INVALID_TOKEN_ERROR)
                )
                val targetUserResourceId =
                    call.parameters["userId"]?.let(::parseOrgResourceId)
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                val targetUserId =
                    membershipService.resolveMemberUserId(orgId, targetUserResourceId)
                        ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Member not found"))

                membershipService.removeMember(orgId, targetUserId, requestingUserId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            }

            // Invite single member
            post("/invitations") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.currentOrgIdOrNull() ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(INVALID_TOKEN_ERROR)
                )

                membershipService.requireRole(orgId, userId, OrgRole.ADMIN)
                val request = call.receive<InviteMemberRequest>()

                val invitation = invitationService.inviteMember(orgId, request.email, request.role, userId)
                call.respond(HttpStatusCode.Created, invitation)
            }

            // Bulk invite
            post("/invitations/bulk") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.currentOrgIdOrNull() ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(INVALID_TOKEN_ERROR)
                )

                val request = call.receive<BulkInviteRequest>()

                val result = invitationService.bulkInvite(orgId, request.emails, request.role, userId)
                call.respond(HttpStatusCode.OK, result)
            }

            // Get pending invitations
            get("/invitations") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.currentOrgIdOrNull() ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(INVALID_TOKEN_ERROR)
                )

                membershipService.requireRole(orgId, userId, OrgRole.ADMIN)

                val invitations = invitationService.getPendingInvitations(orgId)
                call.respond(invitations)
            }

            // Revoke invitation
            delete("/invitations/{invitationId}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val invitationId =
                    call.parameters["invitationId"]?.let(::parseOrgResourceId)
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid invitation ID"))

                invitationService.revokeInvitation(invitationId, userId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            }

            // Resend invitation
            post("/invitations/{invitationId}/resend") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val invitationId =
                    call.parameters["invitationId"]?.let(::parseOrgResourceId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid invitation ID"))

                invitationService.resendInvitation(invitationId, userId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            }

            // Accept invitation (requires auth)
            post("/invitations/accept") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()

                val request = call.receive<AcceptInviteRequest>()

                invitationService.acceptInvitation(request.token, userId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            }
        }

        // Get invitation details (no auth required)
        get("/invitations/details") {
            val token =
                call.request.queryParameters["token"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Token required"))

            val details = invitationService.getInvitationDetails(token)
            call.respond(details)
        }
    }
}
