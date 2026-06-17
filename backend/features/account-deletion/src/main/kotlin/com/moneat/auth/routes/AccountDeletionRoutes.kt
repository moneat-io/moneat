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
import com.moneat.billing.services.StripeService
import com.moneat.notifications.services.EmailService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.GlobalContext
import java.sql.SQLException
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val ORGANIZATION_NOT_FOUND_MESSAGE = "Organization not found"
private const val ORGANIZATION_ROUTE = "/organizations/{orgId}"
private const val INVALID_REQUEST_BODY_MESSAGE = "Invalid request body"
private const val MAX_ORGANIZATION_NAME_LENGTH = 255
private const val MAX_ORGANIZATION_SLUG_LENGTH = 63
private const val POSTGRES_UNIQUE_VIOLATION_STATE = "23505"
private val organizationSlugPattern = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

@Serializable
data class DeleteAccountRequest(
    val confirmation: String
)

@Serializable
data class DeleteOrganizationRequest(
    val confirmation: String
)

@Serializable
data class UpdateOrganizationSettingsRequest(
    val name: String? = null,
    val slug: String? = null,
    val defaultTimezone: String? = null,
)

@Serializable
data class OrgDetailsResponse(
    val id: String,
    val name: String,
    val role: String,
    val slug: String,
    val defaultTimezone: String,
    val dataRegion: String,
    val createdAt: String,
)

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
    deletionService: AccountDeletionService = defaultAccountDeletionService(),
) {
    // Get organization details for account deletion confirmation
    get(ORGANIZATION_ROUTE) { handleGetOrgForDeletion() }
    // Update organization settings
    patch(ORGANIZATION_ROUTE) { handleUpdateOrganizationSettings() }
    // Delete current user account
    delete("/account") { handleDeleteAccount(deletionService) }
    // Delete organization
    delete(ORGANIZATION_ROUTE) { handleDeleteOrganization(deletionService) }
    // Validate account deletion (check if user can delete)
    get("/account/deletion-validation") { handleAccountDeletionValidation(deletionService) }
    // Validate organization deletion (check if user can delete)
    get("/organizations/{orgId}/deletion-validation") { handleOrgDeletionValidation(deletionService) }
}

private fun defaultAccountDeletionService(): AccountDeletionService {
    val koin = GlobalContext.get()
    return AccountDeletionService(
        stripeService = koin.get<StripeService>(),
        emailService = koin.get<EmailService>(),
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetOrgForDeletion() {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()

    val orgWithRole = resolveOrganizationRowFromPath(userId) ?: return

    call.respond(orgDetailsResponse(orgWithRole))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpdateOrganizationSettings() {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()

    val request =
        suspendRunCatching {
            call.receive<UpdateOrganizationSettingsRequest>()
        }.getOrElse { _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_REQUEST_BODY_MESSAGE))
            return
        }

    val orgWithRole = resolveOrganizationRowFromPath(userId) ?: return
    if (orgWithRole[Memberships.role] !in setOf("owner", "admin")) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse("Only organization admins can update organization settings")
        )
        return
    }

    val update = validatedOrganizationUpdate(request, orgWithRole) ?: return
    if (!update.hasChanges()) {
        call.respond(orgDetailsResponse(orgWithRole))
        return
    }

    suspendRunCatching {
        val now = Clock.System.now()
        transaction {
            Organizations.update({ Organizations.id eq orgWithRole[Organizations.id] }) {
                update.name?.let { value -> it[name] = value }
                update.slug?.let { value -> it[slug] = value }
                update.defaultTimezone?.let { value -> it[default_timezone] = value }
                it[updated_at] = now
            }
        }
    }.onFailure { cause ->
        if (update.slug != null && cause.isUniqueConstraintViolation()) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Organization slug is already in use"))
            return
        }
        throw cause
    }

    val updated = resolveOrganizationRowFromPath(userId) ?: return
    call.respond(orgDetailsResponse(updated))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteAccount(
    deletionService: AccountDeletionService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()

    val request =
        suspendRunCatching {
            call.receive<DeleteAccountRequest>()
        }.getOrElse { _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_REQUEST_BODY_MESSAGE))
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

    val orgId = resolveOrganizationIdFromPath(userId) ?: return

    val request =
        suspendRunCatching {
            call.receive<DeleteOrganizationRequest>()
        }.getOrElse { _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_REQUEST_BODY_MESSAGE))
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
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ORGANIZATION_NOT_FOUND_MESSAGE))
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
    val orgId = resolveOrganizationIdFromPath(userId) ?: return
    val validation = deletionService.validateOrganizationDeletion(orgId, userId)
    call.respond(OrgDeletionValidationResponse(canDelete = validation.canDelete, error = validation.errorMessage))
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveOrganizationIdFromPath(userId: Int): Int? {
    val orgResourceId = call.parameters["orgId"]?.let(::parseOrganizationResourceId)
    if (orgResourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
        return null
    }

    val orgId = transaction {
        Memberships
            .innerJoin(Organizations)
            .selectAll()
            .where {
                (Memberships.user_id eq userId) and
                    (Organizations.resource_id eq orgResourceId) and
                    (Organizations.deletedAt.isNull())
            }
            .singleOrNull()
            ?.get(Organizations.id)
    }
    if (orgId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ORGANIZATION_NOT_FOUND_MESSAGE))
    }
    return orgId
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveOrganizationRowFromPath(userId: Int): ResultRow? {
    val orgResourceId = call.parameters["orgId"]?.let(::parseOrganizationResourceId)
    if (orgResourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organization ID"))
        return null
    }

    val orgWithRole =
        transaction {
            Memberships
                .innerJoin(Organizations)
                .selectAll()
                .where {
                    (Memberships.user_id eq userId) and
                        (Organizations.resource_id eq orgResourceId) and
                        (Organizations.deletedAt.isNull())
                }.singleOrNull()
        }

    if (orgWithRole == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ORGANIZATION_NOT_FOUND_MESSAGE))
    }
    return orgWithRole
}

private data class ValidatedOrganizationSettingsUpdate(
    val name: String?,
    val slug: String?,
    val defaultTimezone: String?,
) {
    fun hasChanges(): Boolean =
        name != null || slug != null || defaultTimezone != null
}

private suspend fun io.ktor.server.routing.RoutingContext.validatedOrganizationUpdate(
    request: UpdateOrganizationSettingsRequest,
    current: ResultRow,
): ValidatedOrganizationSettingsUpdate? {
    val name = request.name?.trim()
    if (name != null && (name.isBlank() || name.length > MAX_ORGANIZATION_NAME_LENGTH)) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Organization name must be 1-255 characters"))
        return null
    }

    val slug = request.slug?.trim()?.lowercase(Locale.US)
    if (slug != null && !isValidOrganizationSlug(slug)) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Slug must be 1-63 lowercase letters, numbers, or hyphens")
        )
        return null
    }

    val defaultTimezone = request.defaultTimezone?.trim()?.ifBlank { "UTC" }
    if (defaultTimezone != null && defaultTimezone !in ZoneId.getAvailableZoneIds()) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid timezone identifier"))
        return null
    }

    return ValidatedOrganizationSettingsUpdate(
        name = name?.takeIf { it != current[Organizations.name] },
        slug = slug?.takeIf { it != current[Organizations.slug] },
        defaultTimezone = defaultTimezone?.takeIf { it != current[Organizations.default_timezone] },
    )
}

private fun orgDetailsResponse(row: ResultRow): OrgDetailsResponse =
    OrgDetailsResponse(
        id = row[Organizations.resource_id].toString(),
        name = row[Organizations.name],
        role = row[Memberships.role],
        slug = row[Organizations.slug],
        defaultTimezone = row[Organizations.default_timezone],
        dataRegion = row[Organizations.data_region],
        createdAt = row[Organizations.created_at].toString(),
    )

private fun isValidOrganizationSlug(slug: String): Boolean =
    slug.length <= MAX_ORGANIZATION_SLUG_LENGTH && organizationSlugPattern.matches(slug)

private fun Throwable.isUniqueConstraintViolation(): Boolean {
    val sqlState = (this as? ExposedSQLException)?.sqlState ?: (cause as? SQLException)?.sqlState
    if (sqlState == POSTGRES_UNIQUE_VIOLATION_STATE) return true
    val text = sequenceOf(message, cause?.message).filterNotNull().joinToString(" ")
    return text.contains("unique", ignoreCase = true) &&
        text.contains("slug", ignoreCase = true)
}

private fun parseOrganizationResourceId(value: String): Uuid? =
    value.toUuidOrNull()
