// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.authorization

import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.notifications.services.SlackIdentityResolution
import com.moneat.notifications.services.SlackIdentityStatus
import com.moneat.org.services.OrgRole
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

enum class SlackIncidentAction {
    READ,
    RESPOND,
}

enum class SlackIncidentAccessStatus {
    ALLOWED,
    LINK_REQUIRED,
    FORBIDDEN,
}

data class SlackIncidentAccessRequest(
    val identity: SlackIdentityResolution,
    val organizationId: Int,
    val incidentId: Int,
    val visibility: NativeIncidentVisibility,
    val action: SlackIncidentAction,
)

data class SlackIncidentAccessDecision(
    val status: SlackIncidentAccessStatus,
    val message: String,
) {
    val allowed: Boolean
        get() = status == SlackIncidentAccessStatus.ALLOWED
}

/**
 * Centralizes Slack incident visibility and responder checks. Slack adapters should call this
 * before rendering incident data or executing a mutation; the returned messages are safe for
 * ephemeral Slack responses and never disclose whether a private incident exists.
 */
class SlackIncidentAuthorizationService {
    fun authorize(request: SlackIncidentAccessRequest): SlackIncidentAccessDecision {
        val identity = request.identity
        if (identity.status == SlackIdentityStatus.UNMAPPED) {
            return SlackIncidentAccessDecision(
                SlackIncidentAccessStatus.LINK_REQUIRED,
                "Link your Slack identity to Moneat before accessing incident response actions.",
            )
        }
        if (!identity.isMapped || identity.organizationId != request.organizationId) {
            return denied()
        }
        val userId = identity.userId ?: return denied()
        val role = identity.role ?: return denied()
        if (request.visibility != NativeIncidentVisibility.PRIVATE && request.action == SlackIncidentAction.READ) {
            return allowed()
        }
        if (role.level >= OrgRole.ADMIN.level) {
            return allowed()
        }

        val hasIncidentAccess = transaction {
            val assignedRole = NativeIncidentRoleAssignments
                .selectAll()
                .where {
                    (NativeIncidentRoleAssignments.organizationId eq request.organizationId) and
                        (NativeIncidentRoleAssignments.incidentId eq request.incidentId) and
                        (NativeIncidentRoleAssignments.assigneeUserId eq userId) and
                        NativeIncidentRoleAssignments.endedAt.isNull()
                }
                .limit(1)
                .firstOrNull() != null
            val participant = NativeIncidentParticipants
                .selectAll()
                .where {
                    (NativeIncidentParticipants.organizationId eq request.organizationId) and
                        (NativeIncidentParticipants.incidentId eq request.incidentId) and
                        (NativeIncidentParticipants.userId eq userId) and
                        (NativeIncidentParticipants.participationType eq IncidentParticipationType.PARTICIPANT.wire) and
                        NativeIncidentParticipants.leftAt.isNull()
                }
                .limit(1)
                .firstOrNull() != null
            assignedRole || participant
        }
        if (hasIncidentAccess) return allowed()
        return denied()
    }

    private fun allowed() =
        SlackIncidentAccessDecision(
            SlackIncidentAccessStatus.ALLOWED,
            "Slack incident access granted.",
        )

    private fun denied() =
        SlackIncidentAccessDecision(
            SlackIncidentAccessStatus.FORBIDDEN,
            "You are not authorized to access or respond to this incident in Slack.",
        )
}
