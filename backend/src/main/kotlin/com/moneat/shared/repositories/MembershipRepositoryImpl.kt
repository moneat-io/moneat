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

package com.moneat.shared.repositories

import com.moneat.shared.models.Memberships
import com.moneat.shared.repositories.models.MembershipRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class MembershipRepositoryImpl : MembershipRepository {

    override fun getOrganizationIdsForUser(userId: Int): List<Int> =
        transaction {
            Memberships
                .selectAll()
                .where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }
                .distinct()
        }

    override fun getFirstMembershipForUser(userId: Int): MembershipRow? =
        transaction {
            Memberships
                .selectAll()
                .where { Memberships.user_id eq userId }
                .orderBy(Memberships.id to SortOrder.ASC)
                .firstOrNull()
                ?.let {
                    MembershipRow(
                        id = it[Memberships.id],
                        userId = it[Memberships.user_id],
                        organizationId = it[Memberships.organization_id],
                        role = it[Memberships.role]
                    )
                }
        }
}
