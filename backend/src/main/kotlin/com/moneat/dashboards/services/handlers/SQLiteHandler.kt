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

package com.moneat.dashboards.services.handlers

import java.util.concurrent.ConcurrentHashMap

/**
 * SQLite handler. For SQLite, "host" is the file path (e.g. /path/to/db.sqlite).
 * Port is ignored.
 */
class SQLiteHandler(pools: ConcurrentHashMap<Long, com.zaxxer.hikari.HikariDataSource>) : JdbcHandler(
    driverClass = "org.sqlite.JDBC",
    pools = pools,
) {
    override fun buildJdbcUrl(host: String, port: Int, database: String, options: ConnectionOptions): String {
        val path = host.trim()
        return if (path.startsWith("jdbc:sqlite:")) path else "jdbc:sqlite:$path"
    }

    override fun defaultPort(): Int = 0

    override fun defaultDatabase(): String = ""

    override fun schemaIntrospectionQuery(): String =
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name LIMIT 50"

    override fun testConnectionQuery(): String = schemaIntrospectionQuery()

    override fun schemaFieldsQuery(): String =
        """
        SELECT m.name || '.' || p.name, p.type, ''
        FROM sqlite_master m
        CROSS JOIN pragma_table_info(m.name) p
        WHERE m.type='table'
        ORDER BY m.name, p.cid
        LIMIT 500
        """.trimIndent()

    override fun forbiddenKeywords(): List<Pair<Regex, String>> = JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN
}
