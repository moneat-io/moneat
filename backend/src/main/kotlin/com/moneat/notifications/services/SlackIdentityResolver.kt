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

package com.moneat.notifications.services

import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

enum class SlackIdentityStatus {
    MAPPED,
    UNMAPPED,
    GUEST,
    EXTERNAL,
    REVOKED,
    CROSS_ORGANIZATION,
}

data class SlackIdentityRequest(
    val teamId: String?,
    val enterpriseId: String? = null,
    val userId: String?,
    val organizationId: Int? = null,
    val isGuest: Boolean = false,
    val isExternal: Boolean = false,
)

data class SlackIdentityResolution(
    val status: SlackIdentityStatus,
    val organizationId: Int? = null,
    val userId: Int? = null,
    val role: OrgRole? = null,
    val teamId: String? = null,
    val enterpriseId: String? = null,
    val message: String,
) {
    val isMapped: Boolean
        get() = status == SlackIdentityStatus.MAPPED

    val canRespond: Boolean
        get() = isMapped && role != null
}

/**
 * Resolves a Slack principal against the workspace binding and current Moneat membership.
 * The resolver intentionally returns a safe decision instead of throwing: inbound workers
 * must be able to acknowledge Slack requests without exposing organization data or retrying
 * permanently unauthorized deliveries.
 */
class SlackIdentityResolver {
    fun resolve(request: SlackIdentityRequest): SlackIdentityResolution {
        val teamId = request.teamId?.trim()?.takeIf(String::isNotEmpty)
        val userId = request.userId?.trim()?.takeIf(String::isNotEmpty)
        val enterpriseId = request.enterpriseId?.trim()?.takeIf(String::isNotEmpty)
        if (request.isExternal) {
            return resolution(
                SlackIdentityStatus.EXTERNAL,
                teamId,
                enterpriseId,
                "External Slack users must be linked to an organization member before they can respond.",
            )
        }
        if (request.isGuest) {
            return resolution(
                SlackIdentityStatus.GUEST,
                teamId,
                enterpriseId,
                "Slack guests need an explicit Moneat membership before they can respond.",
            )
        }
        if (teamId == null || userId == null) {
            return resolution(
                SlackIdentityStatus.UNMAPPED,
                teamId,
                enterpriseId,
                LINK_USER_MESSAGE,
            )
        }

        return transaction {
            resolveMappedIdentity(teamId, userId, enterpriseId, request.organizationId)
        }
    }

    private fun resolveMappedIdentity(
        teamId: String,
        slackUserId: String,
        enterpriseId: String?,
        requestedOrganizationId: Int?,
    ): SlackIdentityResolution {
        val mapping = SlackUserMappings
            .selectAll()
            .where {
                (SlackUserMappings.slackTeamId eq teamId) and
                    (SlackUserMappings.slackUserId eq slackUserId)
            }
            .firstOrNull()
            ?: return resolution(SlackIdentityStatus.UNMAPPED, teamId, enterpriseId, LINK_USER_MESSAGE)
        val mappedUserId = mapping[SlackUserMappings.userId]
        val user = Users.selectAll().where { Users.id eq mappedUserId }.firstOrNull()
        if (user == null || user[Users.deletedAt] != null) {
            return resolution(
                SlackIdentityStatus.REVOKED,
                teamId,
                enterpriseId,
                "This Slack identity is no longer active in Moneat. Relink it from the organization settings.",
            )
        }
        return resolveMembership(teamId, enterpriseId, mappedUserId, requestedOrganizationId)
    }

    private fun resolveMembership(
        teamId: String,
        enterpriseId: String?,
        mappedUserId: Int,
        requestedOrganizationId: Int?,
    ): SlackIdentityResolution {
        val installedOrganizations = OrganizationIntegrations
            .selectAll()
            .where {
                (OrganizationIntegrations.integration_type eq "slack") and
                    (OrganizationIntegrations.team_id eq teamId) and
                    (OrganizationIntegrations.enabled eq true)
            }
            .map { it[OrganizationIntegrations.organization_id] }
            .distinct()
        val memberships = Memberships.selectAll().where { Memberships.user_id eq mappedUserId }.toList()
        val membership = memberships.firstOrNull { row ->
            val organizationId = row[Memberships.organization_id]
            (requestedOrganizationId == null && organizationId in installedOrganizations) ||
                organizationId == requestedOrganizationId
        }
        if (membership == null) {
            val status = if (memberships.isEmpty()) {
                SlackIdentityStatus.REVOKED
            } else {
                SlackIdentityStatus.CROSS_ORGANIZATION
            }
            return resolution(
                status,
                teamId,
                enterpriseId,
                "This Slack identity is not a member of the connected Moneat organization.",
            )
        }
        val role = runCatching { OrgRole.fromString(membership[Memberships.role]) }.getOrNull()
            ?: return resolution(
                SlackIdentityStatus.REVOKED,
                teamId,
                enterpriseId,
                "This Moneat membership has an invalid or revoked role.",
            )
        return SlackIdentityResolution(
            status = SlackIdentityStatus.MAPPED,
            organizationId = membership[Memberships.organization_id],
            userId = mappedUserId,
            role = role,
            teamId = teamId,
            enterpriseId = enterpriseId,
            message = "Slack identity mapped to an active Moneat member.",
        )
    }

    private fun resolution(
        status: SlackIdentityStatus,
        teamId: String?,
        enterpriseId: String?,
        message: String,
    ): SlackIdentityResolution =
        SlackIdentityResolution(
            status = status,
            teamId = teamId,
            enterpriseId = enterpriseId,
            message = message,
        )

    companion object {
        const val LINK_USER_MESSAGE =
            "Your Slack identity is not linked to Moneat. " +
                "Ask an organization admin to link your Slack user before responding."
    }
}
