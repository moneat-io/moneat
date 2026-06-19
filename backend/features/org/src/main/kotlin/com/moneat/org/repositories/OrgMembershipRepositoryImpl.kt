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

import com.moneat.org.repositories.models.OrgMemberRow
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class OrgMembershipRepositoryImpl : OrgMembershipRepository {

    override fun getMembers(orgId: Int): List<OrgMemberRow> =
        transaction {
            (Memberships innerJoin Users)
                .selectAll()
                .where { Memberships.organization_id eq orgId }
                .map { row ->
                    OrgMemberRow(
                        userId = row[Users.id],
                        userResourceId = row[Users.resource_id].toString(),
                        email = row[Users.email],
                        name = row[Users.name],
                        role = row[Memberships.role]
                    )
                }
        }

    override fun getMemberRole(orgId: Int, userId: Int): String? =
        transaction {
            Memberships
                .selectAll()
                .where { (Memberships.organization_id eq orgId) and (Memberships.user_id eq userId) }
                .singleOrNull()
                ?.get(Memberships.role)
        }

    override fun updateMemberRole(orgId: Int, targetUserId: Int, newRole: String): Int =
        transaction {
            Memberships.update(
                { (Memberships.organization_id eq orgId) and (Memberships.user_id eq targetUserId) }
            ) {
                it[Memberships.role] = newRole
            }
        }

    override fun removeMember(orgId: Int, targetUserId: Int): Int =
        transaction {
            Memberships.deleteWhere {
                (Memberships.organization_id eq orgId) and (Memberships.user_id eq targetUserId)
            }
        }

    override fun isMember(orgId: Int, userId: Int): Boolean =
        transaction {
            Memberships
                .selectAll()
                .where { (Memberships.organization_id eq orgId) and (Memberships.user_id eq userId) }
                .count() > 0
        }

    override fun getOwnerCount(orgId: Int): Int =
        transaction {
            Memberships
                .selectAll()
                .where { (Memberships.organization_id eq orgId) and (Memberships.role eq "owner") }
                .count()
                .toInt()
        }

    override fun addMember(orgId: Int, userId: Int, role: String) {
        transaction {
            Memberships.insert {
                it[organization_id] = orgId
                it[user_id] = userId
                it[Memberships.role] = role
            }
        }
    }
}
