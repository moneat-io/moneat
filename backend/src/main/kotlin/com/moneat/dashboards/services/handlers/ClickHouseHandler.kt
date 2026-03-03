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

class ClickHouseHandler(pools: ConcurrentHashMap<Long, com.zaxxer.hikari.HikariDataSource>) : JdbcHandler(
    driverClass = "com.clickhouse.jdbc.ClickHouseDriver",
    pools = pools,
) {
    override fun buildJdbcUrl(host: String, port: Int, database: String): String =
        if (database.isBlank()) "jdbc:clickhouse://$host:$port"
        else "jdbc:clickhouse://$host:$port/$database"

    override fun defaultPort(): Int = 8123

    override fun defaultDatabase(): String = "default"

    override fun schemaIntrospectionQuery(): String =
        "SELECT name FROM system.tables WHERE database = currentDatabase() ORDER BY name LIMIT 50"

    override fun testConnectionQuery(): String = schemaIntrospectionQuery()

    override fun schemaFieldsQuery(): String =
        """
        SELECT concat(table, '.', name), type, ''
        FROM system.columns
        WHERE database = currentDatabase()
        ORDER BY table, position
        LIMIT 500
        """.trimIndent()

    override fun forbiddenKeywords(): List<Pair<Regex, String>> = JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN
}
