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

package com.moneat.org.repositories.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Raw invitation record as stored in the DB. */
data class OrgInvitationRow(
    val id: Int,
    val resourceId: Uuid,
    val orgId: Int,
    val email: String,
    val role: String,
    val invitedBy: Int,
    val token: String,
    val status: String,
    val expiresAt: Long,
    val createdAt: Instant,
)

/** Invitation joined with the inviter's display name for listing. */
data class InvitationWithInviterRow(
    val id: Int,
    val resourceId: Uuid,
    val email: String,
    val role: String,
    val status: String,
    val expiresAt: Long,
    val createdAt: Instant,
    val inviterName: String,
    val inviterEmail: String,
)

/** Details response joined with org and inviter. */
data class OrgInvitationDetailsRow(
    val orgName: String,
    val role: String,
    val inviterDisplay: String,
    val expiresAt: Long,
    val status: String,
)

/** Inviter + org info needed when sending an invitation email. */
data class InviterAndOrgRow(
    val inviterName: String?,
    val inviterEmail: String,
    val orgName: String,
)

/** Minimal user info for lookups inside the invitation flow. */
data class OrgInvitationUserRow(
    val id: Int,
    val email: String,
    val name: String?,
)
