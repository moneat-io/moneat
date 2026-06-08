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
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.utils.BooleanResponse
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

private const val INVALID_TOKEN_ERROR = "Invalid token"

fun Route.orgManagementRoutes(
    membershipService: OrgMembershipService = GlobalContext.get().get(),
    invitationService: OrgInvitationService = GlobalContext.get().get(),
) {
    route("/v1/org") {
        authenticate("auth-jwt") {
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
                val targetUserId =
                    call.parameters["userId"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))

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
                val targetUserId =
                    call.parameters["userId"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))

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
                    call.parameters["invitationId"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid invitation ID"))

                invitationService.revokeInvitation(invitationId, userId)
                call.respond(HttpStatusCode.OK, BooleanResponse(true))
            }

            // Resend invitation
            post("/invitations/{invitationId}/resend") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val invitationId =
                    call.parameters["invitationId"]?.toIntOrNull()
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
