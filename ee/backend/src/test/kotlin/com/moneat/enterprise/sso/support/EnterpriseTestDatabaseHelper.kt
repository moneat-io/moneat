// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.support

import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Mirrors core [com.moneat.testsupport.TestDatabaseHelper] for enterprise unit tests.
 * Duplicated so the ee module does not depend on backend test outputs.
 */
object EnterpriseTestDatabaseHelper {

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
