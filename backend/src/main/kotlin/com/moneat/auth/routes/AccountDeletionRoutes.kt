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

package com.moneat.auth.routes

import com.moneat.auth.services.AccountDeletionService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext

private val logger = KotlinLogging.logger {}

@Serializable
data class DeleteAccountRequest(
    val confirmation: String
)

@Serializable
data class DeleteOrganizationRequest(
    val confirmation: String
)

@Serializable
data class OrgDetailsResponse(val id: Int, val name: String, val role: String)

@Serializable
data class UserDeletionValidationResponse(
    val canDelete: Boolean,
    val error: String? = null,
    val organizationsAsLastOwner: List<String> = emptyList()
)

@Serializable
data class OrgDeletionValidationResponse(val canDelete: Boolean, val error: String? = null)

@Serializable
data class CannotDeleteUserResponse(val error: String?, val organizations: List<String>)

fun Route.accountDeletionRoutes(
    deletionService: AccountDeletionService = GlobalContext.get().get(),
) {
    // Get organization details for account deletion confirmation
    get("/organizations/{orgId}") { handleGetOrgForDeletion() }
    // Delete current user account
    delete("/account") { handleDeleteAccount(deletionService) }
    // Delete organization
    delete("/organizations/{orgId}") { handleDeleteOrganization(deletionService) }
    // Validate account deletion (check if user can delete)
    get("/account/deletion-validation") { handleAccountDeletionValidation(deletionService) }
    // Validate organization deletion (check if user can delete)
    get("/organizations/{orgId}/deletion-validation") { handleOrgDeletionValidation(deletionService) }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetOrgForDeletion() {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()

    val orgId = call.parameters["orgId"]?.toIntOrNull()
    if (orgId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
        return
    }

    val orgWithRole =
        transaction {
            Memberships
                .innerJoin(Organizations)
                .selectAll()
                .where {
                    (Memberships.user_id eq userId) and
                        (Memberships.organization_id eq orgId) and
                        (Organizations.deletedAt.isNull())
                }.singleOrNull()
        }

    if (orgWithRole == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Organization not found"))
        return
    }

    call.respond(
        OrgDetailsResponse(
            id = orgId,
            name = orgWithRole[Organizations.name],
            role = orgWithRole[Memberships.role]
        )
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteAccount(
    deletionService: AccountDeletionService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()

    val request =
        try {
            call.receive<DeleteAccountRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            return
        }

    val userEmail =
        transaction {
            com.moneat.shared.models.Users
                .selectAll()
                .where { com.moneat.shared.models.Users.id eq userId }
                .singleOrNull()
                ?.get(com.moneat.shared.models.Users.email)
        }

    if (userEmail == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
        return
    }

    if (!request.confirmation.trim().equals(userEmail, ignoreCase = true)) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Confirmation does not match your email address"))
        return
    }

    val validation = deletionService.validateUserDeletion(userId)
    if (!validation.canDelete) {
        call.respond(
            HttpStatusCode.BadRequest,
            CannotDeleteUserResponse(
                error = validation.errorMessage,
                organizations = validation.organizationsAsLastOwner
            )
        )
        return
    }

    val success = deletionService.deleteUserAccount(userId)
    if (success) {
        logger.info { "User account deleted: $userId" }
        call.respond(HttpStatusCode.OK, mapOf("message" to "Account deleted successfully"))
    } else {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete account"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteOrganization(
    deletionService: AccountDeletionService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()

    val orgId = call.parameters["orgId"]?.toIntOrNull()
    if (orgId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
        return
    }

    val request =
        try {
            call.receive<DeleteOrganizationRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            return
        }

    val orgName =
        transaction {
            Organizations
                .selectAll()
                .where { Organizations.id eq orgId }
                .singleOrNull()
                ?.get(Organizations.name)
        }

    if (orgName == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Organization not found"))
        return
    }

    if (request.confirmation != orgName) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Confirmation does not match organization name"))
        return
    }

    val validation = deletionService.validateOrganizationDeletion(orgId, userId)
    if (!validation.canDelete) {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to validation.errorMessage))
        return
    }

    val success = deletionService.deleteOrganization(orgId, userId)
    if (success) {
        logger.info { "Organization deleted: $orgId by user $userId" }
        call.respond(HttpStatusCode.OK, mapOf("message" to "Organization deleted successfully"))
    } else {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete organization"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleAccountDeletionValidation(
    deletionService: AccountDeletionService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val validation = deletionService.validateUserDeletion(userId)
    call.respond(
        UserDeletionValidationResponse(
            canDelete = validation.canDelete,
            error = validation.errorMessage,
            organizationsAsLastOwner = validation.organizationsAsLastOwner
        )
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleOrgDeletionValidation(
    deletionService: AccountDeletionService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = call.parameters["orgId"]?.toIntOrNull()
    if (orgId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
        return
    }
    val validation = deletionService.validateOrganizationDeletion(orgId, userId)
    call.respond(OrgDeletionValidationResponse(canDelete = validation.canDelete, error = validation.errorMessage))
}
