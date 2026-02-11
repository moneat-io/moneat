package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.EmailService
import com.moneat.services.OrgInvitationService
import com.moneat.services.OrgMembershipService
import com.moneat.services.OrgRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.orgManagementRoutes() {
    val membershipService = OrgMembershipService()
    val invitationService = OrgInvitationService(membershipService, EmailService())
    
    route("/v1/org") {
        authenticate("auth-jwt") {
            // Get all members and pending invitations
            get("/members") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()
                
                membershipService.requireRole(orgId, userId, OrgRole.MEMBER)
                
                val members = membershipService.getMembers(orgId)
                val pendingInvitations = invitationService.getPendingInvitations(orgId)
                
                call.respond(OrgMembersResponse(members, pendingInvitations))
            }
            
            // Update member role
            put("/members/{userId}/role") {
                val principal = call.principal<JWTPrincipal>()!!
                val requestingUserId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()
                val targetUserId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                
                val request = call.receive<UpdateMemberRoleRequest>()
                
                membershipService.updateMemberRole(orgId, targetUserId, request.role, requestingUserId)
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            }
            
            // Remove member
            delete("/members/{userId}") {
                val principal = call.principal<JWTPrincipal>()!!
                val requestingUserId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()
                val targetUserId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                
                membershipService.removeMember(orgId, targetUserId, requestingUserId)
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            }
            
            // Invite single member
            post("/invitations") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()
                
                val request = call.receive<InviteMemberRequest>()
                
                val invitation = invitationService.inviteMember(orgId, request.email, request.role, userId)
                call.respond(HttpStatusCode.Created, invitation)
            }
            
            // Bulk invite
            post("/invitations/bulk") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()
                
                val request = call.receive<BulkInviteRequest>()
                
                val result = invitationService.bulkInvite(orgId, request.emails, request.role, userId)
                call.respond(HttpStatusCode.OK, result)
            }
            
            // Get pending invitations
            get("/invitations") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val orgId = principal.payload.getClaim("orgId").asInt()
                
                membershipService.requireRole(orgId, userId, OrgRole.ADMIN)
                
                val invitations = invitationService.getPendingInvitations(orgId)
                call.respond(invitations)
            }
            
            // Revoke invitation
            delete("/invitations/{invitationId}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val invitationId = call.parameters["invitationId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid invitation ID"))
                
                invitationService.revokeInvitation(invitationId, userId)
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            }
            
            // Resend invitation
            post("/invitations/{invitationId}/resend") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val invitationId = call.parameters["invitationId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid invitation ID"))
                
                invitationService.resendInvitation(invitationId, userId)
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            }
            
            // Accept invitation (requires auth)
            post("/invitations/accept") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                
                val request = call.receive<AcceptInviteRequest>()
                
                invitationService.acceptInvitation(request.token, userId)
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            }
        }
        
        // Get invitation details (no auth required)
        get("/invitations/details") {
            val token = call.request.queryParameters["token"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Token required"))
            
            val details = invitationService.getInvitationDetails(token)
            call.respond(details)
        }
    }
}
