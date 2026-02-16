// Moneat - Mobile-First Error Monitoring Platform
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

package com.moneat.routes

import com.moneat.models.Organizations
import com.moneat.models.Memberships
import com.moneat.services.AccountDeletionService
import io.ktor.http.HttpStatusCode
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.BooleanResponse
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.receive
import io.ktor.server.request.request
import io.ktor.server.response.respond
import io.ktor.server.response.response
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

@Serializable
data class DeleteAccountRequest(
    val confirmation: String
)

@Serializable
data class DeleteOrganizationRequest(
    val confirmation: String
)

fun Route.accountDeletionRoutes() {
    val deletionService = AccountDeletionService()

    // Get organization details for account deletion confirmation
    get("/organizations/{orgId}") {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal!!.payload.getClaim("userId").asInt()

        val orgId = call.parameters["orgId"]?.toIntOrNull()
        if (orgId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
            return@get
        }

        val orgWithRole = transaction {
            Memberships.innerJoin(Organizations)
                .selectAll()
                .where {
                    (Memberships.user_id eq userId) and
                    (Memberships.organization_id eq orgId) and
                    (Organizations.deletedAt.isNull())
                }
                .singleOrNull()
        }

        if (orgWithRole == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Organization not found"))
            return@get
        }

        call.respond(mapOf(
            "id" to orgId,
            "name" to orgWithRole[Organizations.name],
            "role" to orgWithRole[Memberships.role]
        ))
    }
    
    // Delete current user account
    delete("/account") {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal!!.payload.getClaim("userId").asInt()
        
        val request = try {
            call.receive<DeleteAccountRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            return@delete
        }
        
        // Validate confirmation - user must type their email
        val userEmail = transaction {
            com.moneat.models.Users.selectAll()
                .where { com.moneat.models.Users.id eq userId }
                .singleOrNull()
                ?.get(com.moneat.models.Users.email)
        }
        
        if (userEmail == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
            return@delete
        }
        
        if (!request.confirmation.trim().equals(userEmail, ignoreCase = true)) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Confirmation does not match your email address"
            ))
            return@delete
        }
        
        // Validate deletion is allowed
        val validation = deletionService.validateUserDeletion(userId)
        if (!validation.canDelete) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to validation.errorMessage,
                "organizations" to validation.organizationsAsLastOwner
            ))
            return@delete
        }
        
        // Perform deletion
        val success = deletionService.deleteUserAccount(userId)
        if (success) {
            logger.info { "User account deleted: $userId" }
            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Account deleted successfully"
            ))
        } else {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "Failed to delete account"
            ))
        }
    }
    
    // Delete organization
    delete("/organizations/{orgId}") {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal!!.payload.getClaim("userId").asInt()
        
        val orgId = call.parameters["orgId"]?.toIntOrNull()
        if (orgId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
            return@delete
        }
        
        val request = try {
            call.receive<DeleteOrganizationRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            return@delete
        }
        
        // Get organization name for confirmation
        val orgName = transaction {
            Organizations.selectAll()
                .where { Organizations.id eq orgId }
                .singleOrNull()
                ?.get(Organizations.name)
        }
        
        if (orgName == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Organization not found"))
            return@delete
        }
        
        // Validate confirmation - user must type organization name
        if (request.confirmation != orgName) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Confirmation does not match organization name"
            ))
            return@delete
        }
        
        // Validate deletion is allowed
        val validation = deletionService.validateOrganizationDeletion(orgId, userId)
        if (!validation.canDelete) {
            call.respond(HttpStatusCode.Forbidden, mapOf(
                "error" to validation.errorMessage
            ))
            return@delete
        }
        
        // Perform deletion
        val success = deletionService.deleteOrganization(orgId, userId)
        if (success) {
            logger.info { "Organization deleted: $orgId by user $userId" }
            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Organization deleted successfully"
            ))
        } else {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "Failed to delete organization"
            ))
        }
    }
    
    // Validate account deletion (check if user can delete)
    get("/account/deletion-validation") {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal!!.payload.getClaim("userId").asInt()
        
        val validation = deletionService.validateUserDeletion(userId)
        call.respond(mapOf(
            "canDelete" to validation.canDelete,
            "error" to validation.errorMessage,
            "organizationsAsLastOwner" to validation.organizationsAsLastOwner
        ))
    }
    
    // Validate organization deletion (check if user can delete)
    get("/organizations/{orgId}/deletion-validation") {
        val principal = call.principal<JWTPrincipal>()
        val userId = principal!!.payload.getClaim("userId").asInt()
        
        val orgId = call.parameters["orgId"]?.toIntOrNull()
        if (orgId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
            return@get
        }
        
        val validation = deletionService.validateOrganizationDeletion(orgId, userId)
        call.respond(mapOf(
            "canDelete" to validation.canDelete,
            "error" to validation.errorMessage
        ))
    }
}
