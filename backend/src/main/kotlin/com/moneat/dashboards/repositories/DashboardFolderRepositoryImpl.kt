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

package com.moneat.dashboards.repositories

import com.moneat.dashboards.models.DashboardFolders
import com.moneat.dashboards.repositories.models.DashboardFolderRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

class DashboardFolderRepositoryImpl : DashboardFolderRepository {

    override fun listByOrgId(orgId: Long): List<DashboardFolderRow> =
        transaction {
            DashboardFolders
                .selectAll()
                .where { DashboardFolders.orgId eq orgId }
                .orderBy(DashboardFolders.sortOrder, SortOrder.ASC)
                .map { row ->
                    DashboardFolderRow(
                        id = row[DashboardFolders.id],
                        orgId = row[DashboardFolders.orgId],
                        name = row[DashboardFolders.name],
                        color = row[DashboardFolders.color],
                        sortOrder = row[DashboardFolders.sortOrder],
                        createdAt = row[DashboardFolders.createdAt],
                        updatedAt = row[DashboardFolders.updatedAt]
                    )
                }
        }

    override fun getByIdAndOrgId(id: Long, orgId: Long): DashboardFolderRow? =
        transaction {
            DashboardFolders
                .selectAll()
                .where { (DashboardFolders.id eq id) and (DashboardFolders.orgId eq orgId) }
                .firstOrNull()
                ?.let { row ->
                    DashboardFolderRow(
                        id = row[DashboardFolders.id],
                        orgId = row[DashboardFolders.orgId],
                        name = row[DashboardFolders.name],
                        color = row[DashboardFolders.color],
                        sortOrder = row[DashboardFolders.sortOrder],
                        createdAt = row[DashboardFolders.createdAt],
                        updatedAt = row[DashboardFolders.updatedAt]
                    )
                }
        }

    override fun create(orgId: Long, name: String, color: String?, sortOrder: Int): Long =
        transaction {
            val now = Clock.System.now()
            DashboardFolders.insert {
                it[DashboardFolders.orgId] = orgId
                it[DashboardFolders.name] = name
                it[DashboardFolders.color] = color
                it[DashboardFolders.sortOrder] = sortOrder
                it[DashboardFolders.createdAt] = now
                it[DashboardFolders.updatedAt] = now
            } get DashboardFolders.id
        }

    override fun update(id: Long, orgId: Long, name: String?, color: String?, sortOrder: Int?) {
        transaction {
            val now = Clock.System.now()
            DashboardFolders.update({ (DashboardFolders.id eq id) and (DashboardFolders.orgId eq orgId) }) {
                name?.let { n -> it[DashboardFolders.name] = n }
                color?.let { c -> it[DashboardFolders.color] = c }
                sortOrder?.let { so -> it[DashboardFolders.sortOrder] = so }
                it[DashboardFolders.updatedAt] = now
            }
        }
    }

    override fun delete(id: Long, orgId: Long): Int =
        transaction {
            DashboardFolders.deleteWhere {
                (DashboardFolders.id eq id) and (DashboardFolders.orgId eq orgId)
            }
        }
}
