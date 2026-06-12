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

package com.moneat.shared.services

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

fun organizationResourceId(organizationId: Int): String =
    withTransactionIfNeeded {
        Organizations
            .selectAll()
            .where { Organizations.id eq organizationId }
            .firstOrNull()
            ?.get(Organizations.resource_id)
            ?.toString()
            ?: error("Missing resource_id for organization $organizationId")
    }

fun organizationResourceId(organizationId: Long): String =
    organizationResourceId(organizationId.toInt())

fun organizationResourceIds(organizationIds: Collection<Int>): Map<Int, String> {
    if (organizationIds.isEmpty()) return emptyMap()
    return withTransactionIfNeeded {
        Organizations
            .selectAll()
            .where { Organizations.id inList organizationIds.distinct() }
            .associate { row -> row[Organizations.id] to row[Organizations.resource_id].toString() }
    }
}

fun userResourceId(userId: Int): String =
    withTransactionIfNeeded {
        Users
            .selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.get(Users.resource_id)
            ?.toString()
            ?: error("Missing resource_id for user $userId")
    }

fun userResourceId(userId: Long): String =
    userResourceId(userId.toInt())

fun userResourceIdOrNull(userId: Int?): String? {
    if (userId == null) return null
    return withTransactionIfNeeded {
        Users
            .selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.get(Users.resource_id)
            ?.toString()
    }
}

fun userResourceIds(userIds: Collection<Int>): Map<Int, String> {
    if (userIds.isEmpty()) return emptyMap()
    return withTransactionIfNeeded {
        Users
            .selectAll()
            .where { Users.id inList userIds.distinct() }
            .associate { row -> row[Users.id] to row[Users.resource_id].toString() }
    }
}

fun Map<Int, String>.requireResourceId(id: Int, label: String): String =
    this[id] ?: error("Missing resource_id for $label $id")

fun resolveScopedIntResourceId(
    table: IntIdTable,
    resourceIdColumn: Column<Uuid>,
    scopeColumn: Column<Int>,
    scopeId: Int,
    resourceId: Uuid,
): Int? =
    withTransactionIfNeeded {
        table
            .selectAll()
            .where { (resourceIdColumn eq resourceId) and (scopeColumn eq scopeId) }
            .firstOrNull()
            ?.get(table.id)
            ?.value
    }

fun resolveGlobalIntResourceId(
    table: IntIdTable,
    resourceIdColumn: Column<Uuid>,
    resourceId: Uuid,
): Int? =
    withTransactionIfNeeded {
        table
            .selectAll()
            .where { resourceIdColumn eq resourceId }
            .firstOrNull()
            ?.get(table.id)
            ?.value
    }

data class ScopedIntColumnResourceTable(
    val table: Table,
    val idColumn: Column<Int>,
    val resourceIdColumn: Column<Uuid>,
    val scopeColumn: Column<Int>,
)

fun resolveScopedIntColumnResourceId(
    resourceTable: ScopedIntColumnResourceTable,
    scopeId: Int,
    resourceId: Uuid,
): Int? =
    withTransactionIfNeeded {
        resourceTable.table
            .selectAll()
            .where {
                (resourceTable.resourceIdColumn eq resourceId) and
                    (resourceTable.scopeColumn eq scopeId)
            }
            .firstOrNull()
            ?.get(resourceTable.idColumn)
    }

private fun <T> withTransactionIfNeeded(block: () -> T): T =
    if (TransactionManager.currentOrNull() == null) {
        transaction { block() }
    } else {
        block()
    }
