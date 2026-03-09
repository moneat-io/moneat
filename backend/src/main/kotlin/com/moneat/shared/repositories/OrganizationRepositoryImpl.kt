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

import com.moneat.shared.repositories.models.OrganizationRow
import com.moneat.shared.models.Organizations
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class OrganizationRepositoryImpl : OrganizationRepository {

    override fun findById(id: Int): OrganizationRow? =
        transaction {
            Organizations.selectAll().where { Organizations.id eq id }.firstOrNull()
                ?.let { OrganizationRow(it[Organizations.id], it[Organizations.name], it[Organizations.slug]) }
        }

    override fun findBySlug(slug: String): OrganizationRow? =
        transaction {
            Organizations.selectAll().where { Organizations.slug eq slug }.firstOrNull()
                ?.let { OrganizationRow(it[Organizations.id], it[Organizations.name], it[Organizations.slug]) }
        }
}
