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

package com.moneat.testsupport

import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Workaround for Exposed 1.0.0 bug where [AutoIncColumnType] delegates its
 * `nullable` setter to its inner delegate type, causing column type mutation
 * on singleton Table objects across test runs.
 *
 * Uses H2's `DROP ALL OBJECTS` to avoid FK ordering issues during cleanup,
 * and resets autoIncrement column nullable flags before recreating schemas.
 */
object TestDatabaseHelper {

    /**
     * Drops all H2 objects and recreates the given tables with clean state.
     * Must be called after setting `TransactionManager.defaultDatabase`.
     */
    fun resetSchema(vararg tables: Table) {
        transaction {
            exec("DROP ALL OBJECTS")
        }
        resetAutoIncNullable(*tables)
        transaction {
            SchemaUtils.create(*tables)
        }
    }

    private fun resetAutoIncNullable(vararg tables: Table) {
        tables.forEach { table ->
            table.columns.forEach { column ->
                val colType = column.columnType
                if (colType is AutoIncColumnType) {
                    colType.nullable = false
                }
            }
        }
    }
}
