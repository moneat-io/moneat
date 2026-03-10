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

package com.moneat.org.repositories

import com.moneat.org.repositories.models.InvitationWithInviterRow
import com.moneat.org.repositories.models.InviterAndOrgRow
import com.moneat.org.repositories.models.OrgInvitationDetailsRow
import com.moneat.org.repositories.models.OrgInvitationRow
import com.moneat.org.repositories.models.OrgInvitationUserRow
import kotlin.time.Instant

/**
 * Repository for org invitation data access.
 */
interface OrgInvitationRepository {
    fun expireStaleInvitations(orgId: Int, email: String, nowMs: Long): Int
    fun expireAllStaleForOrg(orgId: Int, nowMs: Long): Int
    fun findUserByEmail(email: String): OrgInvitationUserRow?
    fun existsPendingInvitation(orgId: Int, email: String, nowMs: Long): Boolean
    fun createInvitation(
        orgId: Int,
        email: String,
        role: String,
        invitedBy: Int,
        token: String,
        expiresAt: Long,
        createdAt: Instant,
    ): Int
    fun findInviterAndOrg(invitedBy: Int, orgId: Int): InviterAndOrgRow?
    fun findPendingInvitations(orgId: Int, nowMs: Long): List<InvitationWithInviterRow>
    fun findInvitationDetails(token: String): OrgInvitationDetailsRow?
    fun findByToken(token: String): OrgInvitationRow?
    fun findById(id: Int): OrgInvitationRow?
    fun findUserById(userId: Int): OrgInvitationUserRow?
    fun updateStatus(id: Int, status: String): Int
    fun updateTokenAndExpiry(id: Int, newToken: String, newExpiresAt: Long): Int
    fun findInviterAndOrgForResend(id: Int): InviterAndOrgRow?
    fun cleanupExpiredInvitations(nowMs: Long): Int
    fun purgeOldInvitations(cutoff: Instant): Int
}
