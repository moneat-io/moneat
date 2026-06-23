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

class MariaDBHandler(pools: ConcurrentHashMap<Long, com.zaxxer.hikari.HikariDataSource>) : JdbcHandler(
    driverClass = "org.mariadb.jdbc.Driver",
    pools = pools,
) {
    override fun buildJdbcUrl(host: String, port: Int, database: String, options: ConnectionOptions): String {
        val base = if (database.isBlank()) "jdbc:mariadb://$host:$port/" else "jdbc:mariadb://$host:$port/$database"
        val sslMode = JdbcHandlerCommon.mariadbSslMode(options.tlsMode)
        return if (sslMode != null) "$base?sslMode=$sslMode" else base
    }

    override fun defaultPort(): Int = 3306

    override fun defaultDatabase(): String = ""

    override fun schemaIntrospectionQuery(): String =
        "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name LIMIT 50"

    override fun testConnectionQuery(): String =
        "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = IFNULL(DATABASE(), SCHEMA()) ORDER BY table_name LIMIT 50"

    override fun schemaFieldsQuery(): String =
        """
        SELECT CONCAT(table_name, '.', column_name), data_type, ''
        FROM information_schema.columns
        WHERE table_schema = IFNULL(DATABASE(), SCHEMA())
        ORDER BY table_name, ordinal_position
        LIMIT 500
        """.trimIndent()

    override fun forbiddenKeywords(): List<Pair<Regex, String>> = JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN
}
