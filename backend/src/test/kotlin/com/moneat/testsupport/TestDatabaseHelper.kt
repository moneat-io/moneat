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
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
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

    /**
     * Drops all H2 objects, resets auto-increment columns, and patches JSONB
     * columns to TEXT for H2 compatibility, then creates all tables.
     *
     * Must be called after setting `TransactionManager.defaultDatabase`.
     */
    fun resetSchemaForH2WithJsonb(vararg tables: Table) {
        transaction {
            exec("DROP ALL OBJECTS")
        }
        resetAutoIncNullable(*tables)
        patchJsonbForH2(*tables)
        transaction {
            SchemaUtils.create(*tables)
        }
    }

    /**
     * Drops all H2 objects, resets auto-increment columns, and patches JSONB
     * columns to TEXT for H2 compatibility **without** creating tables via
     * SchemaUtils. Use when the caller provides manual DDL (e.g. tables with
     * JSONB defaults that H2 cannot parse from Exposed's generated SQL).
     */
    fun dropAndPatchJsonb(vararg tables: Table) {
        transaction {
            exec("DROP ALL OBJECTS")
        }
        resetAutoIncNullable(*tables)
        patchJsonbForH2(*tables)
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

    /**
     * Replaces JSONB column types with H2-compatible TEXT-based types so
     * Exposed insert/select operations work without PGobject. Required
     * because PostgreSQL JsonbColumnType uses PGobject which H2 does not
     * support. Uses reflection to swap columnType; documented as necessary
     * workaround for H2 test compatibility.
     */
    private fun patchJsonbForH2(vararg tables: Table) {
        val field = Column::class.java.getDeclaredField("columnType")
        field.isAccessible = true
        val jsonbClassNames = listOf(
            "com.moneat.dashboards.models.JsonbColumnType",
            "com.moneat.shared.models.JsonbColumnType"
        )
        tables.forEach { table ->
            table.columns.forEach { col ->
                val qn = col.columnType::class.qualifiedName
                if (qn != null && jsonbClassNames.any { qn == it }) {
                    field.set(col, h2TextJson(col.columnType.nullable))
                }
            }
        }
    }

    private fun h2TextJson(nullableColumn: Boolean): ColumnType<String> =
        object : ColumnType<String>() {
            override var nullable: Boolean = nullableColumn
            override fun sqlType(): String = "TEXT"
            override fun valueFromDB(value: Any): String = when (value) {
                is String -> value
                else -> value.toString()
            }
            override fun notNullValueToDB(value: String): Any = value
            override fun nonNullValueToString(value: String): String =
                "'${value.replace("'", "''")}'"
        }
}
