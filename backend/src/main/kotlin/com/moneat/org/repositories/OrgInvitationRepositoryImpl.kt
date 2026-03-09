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
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

class OrgInvitationRepositoryImpl : OrgInvitationRepository {

    override fun expireStaleInvitations(orgId: Int, email: String, nowMs: Long): Int =
        transaction {
            OrgInvitations.update({
                (OrgInvitations.organization_id eq orgId) and
                    (OrgInvitations.email eq email) and
                    (OrgInvitations.status eq "pending") and
                    (OrgInvitations.expires_at lessEq nowMs)
            }) {
                it[OrgInvitations.status] = "expired"
            }
        }

    override fun expireAllStaleForOrg(orgId: Int, nowMs: Long): Int =
        transaction {
            OrgInvitations.update({
                (OrgInvitations.organization_id eq orgId) and
                    (OrgInvitations.status eq "pending") and
                    (OrgInvitations.expires_at lessEq nowMs)
            }) {
                it[OrgInvitations.status] = "expired"
            }
        }

    override fun findUserByEmail(email: String): OrgInvitationUserRow? =
        transaction {
            Users.selectAll().where { Users.email eq email }.singleOrNull()?.let { row ->
                OrgInvitationUserRow(
                    id = row[Users.id],
                    email = row[Users.email],
                    name = row[Users.name],
                )
            }
        }

    override fun existsPendingInvitation(orgId: Int, email: String, nowMs: Long): Boolean =
        transaction {
            OrgInvitations.selectAll().where {
                (OrgInvitations.organization_id eq orgId) and
                    (OrgInvitations.email eq email) and
                    (OrgInvitations.status eq "pending") and
                    (OrgInvitations.expires_at greater nowMs)
            }.count() > 0
        }

    override fun createInvitation(
        orgId: Int,
        email: String,
        role: String,
        invitedBy: Int,
        token: String,
        expiresAt: Long,
        createdAt: Instant,
    ): Int =
        transaction {
            OrgInvitations.insert {
                it[organization_id] = orgId
                it[OrgInvitations.email] = email
                it[OrgInvitations.role] = role
                it[OrgInvitations.invited_by] = invitedBy
                it[OrgInvitations.token] = token
                it[OrgInvitations.status] = "pending"
                it[OrgInvitations.expires_at] = expiresAt
                it[OrgInvitations.created_at] = createdAt
            } get OrgInvitations.id
        }

    override fun findInviterAndOrg(invitedBy: Int, orgId: Int): InviterAndOrgRow? =
        transaction {
            val inviter = Users.selectAll().where { Users.id eq invitedBy }.singleOrNull() ?: return@transaction null
            val org = Organizations.selectAll().where { Organizations.id eq orgId }.singleOrNull() ?: return@transaction null
            InviterAndOrgRow(
                inviterName = inviter[Users.name],
                inviterEmail = inviter[Users.email],
                orgName = org[Organizations.name],
            )
        }

    override fun findPendingInvitations(orgId: Int, nowMs: Long): List<InvitationWithInviterRow> =
        transaction {
            (OrgInvitations innerJoin Users)
                .selectAll()
                .where {
                    (OrgInvitations.organization_id eq orgId) and
                        (OrgInvitations.status eq "pending") and
                        (OrgInvitations.expires_at greater nowMs)
                }.map { row ->
                    InvitationWithInviterRow(
                        id = row[OrgInvitations.id],
                        email = row[OrgInvitations.email],
                        role = row[OrgInvitations.role],
                        status = row[OrgInvitations.status],
                        expiresAt = row[OrgInvitations.expires_at],
                        createdAt = row[OrgInvitations.created_at],
                        inviterName = row[Users.name] ?: "",
                        inviterEmail = row[Users.email],
                    )
                }
        }

    override fun findInvitationDetails(token: String): OrgInvitationDetailsRow? =
        transaction {
            (OrgInvitations innerJoin Organizations innerJoin Users)
                .selectAll()
                .where { OrgInvitations.token eq token }
                .singleOrNull()
                ?.let { row ->
                    OrgInvitationDetailsRow(
                        orgName = row[Organizations.name],
                        role = row[OrgInvitations.role],
                        inviterDisplay = row[Users.name] ?: row[Users.email],
                        expiresAt = row[OrgInvitations.expires_at],
                        status = row[OrgInvitations.status],
                    )
                }
        }

    override fun findByToken(token: String): OrgInvitationRow? =
        transaction {
            OrgInvitations.selectAll().where { OrgInvitations.token eq token }.singleOrNull()?.toRow()
        }

    override fun findById(id: Int): OrgInvitationRow? =
        transaction {
            OrgInvitations.selectAll().where { OrgInvitations.id eq id }.singleOrNull()?.toRow()
        }

    override fun findUserById(userId: Int): OrgInvitationUserRow? =
        transaction {
            Users.selectAll().where { Users.id eq userId }.singleOrNull()?.let { row ->
                OrgInvitationUserRow(
                    id = row[Users.id],
                    email = row[Users.email],
                    name = row[Users.name],
                )
            }
        }

    override fun updateStatus(id: Int, status: String): Int =
        transaction {
            OrgInvitations.update({ OrgInvitations.id eq id }) {
                it[OrgInvitations.status] = status
            }
        }

    override fun updateTokenAndExpiry(id: Int, newToken: String, newExpiresAt: Long): Int =
        transaction {
            OrgInvitations.update({ OrgInvitations.id eq id }) {
                it[token] = newToken
                it[expires_at] = newExpiresAt
            }
        }

    override fun findInviterAndOrgForResend(id: Int): InviterAndOrgRow? =
        transaction {
            (OrgInvitations innerJoin Organizations innerJoin Users)
                .selectAll()
                .where { OrgInvitations.id eq id }
                .singleOrNull()
                ?.let { row ->
                    InviterAndOrgRow(
                        inviterName = row[Users.name],
                        inviterEmail = row[Users.email],
                        orgName = row[Organizations.name],
                    )
                }
        }

    override fun cleanupExpiredInvitations(nowMs: Long): Int =
        transaction {
            OrgInvitations.update(
                { (OrgInvitations.status eq "pending") and (OrgInvitations.expires_at less nowMs) }
            ) {
                it[status] = "expired"
            }
        }

    override fun purgeOldInvitations(cutoff: Instant): Int =
        transaction {
            OrgInvitations.deleteWhere {
                (OrgInvitations.status inList listOf("expired", "revoked", "accepted")) and
                    (OrgInvitations.created_at less cutoff)
            }
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRow(): OrgInvitationRow =
        OrgInvitationRow(
            id = this[OrgInvitations.id],
            orgId = this[OrgInvitations.organization_id],
            email = this[OrgInvitations.email],
            role = this[OrgInvitations.role],
            invitedBy = this[OrgInvitations.invited_by],
            token = this[OrgInvitations.token],
            status = this[OrgInvitations.status],
            expiresAt = this[OrgInvitations.expires_at],
            createdAt = this[OrgInvitations.created_at],
        )
}
