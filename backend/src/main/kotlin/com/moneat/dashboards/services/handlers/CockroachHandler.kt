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

/** CockroachDB uses PostgreSQL wire protocol - same driver and schema queries as Postgres. */
class CockroachHandler(pools: ConcurrentHashMap<Long, com.zaxxer.hikari.HikariDataSource>) : JdbcHandler(
    driverClass = "org.postgresql.Driver",
    pools = pools,
) {
    override fun buildJdbcUrl(host: String, port: Int, database: String): String {
        val db = if (database.isBlank()) "defaultdb" else database
        return "jdbc:postgresql://$host:$port/$db"
    }

    override fun defaultPort(): Int = 26257

    override fun defaultDatabase(): String = "defaultdb"

    override fun schemaIntrospectionQuery(): String =
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name LIMIT 50"

    override fun testConnectionQuery(): String = schemaIntrospectionQuery()

    override fun schemaFieldsQuery(): String =
        """
        SELECT table_name || '.' || column_name, data_type, ''
        FROM information_schema.columns
        WHERE table_schema = 'public'
        ORDER BY table_name, ordinal_position
        LIMIT 500
        """.trimIndent()

    override fun forbiddenKeywords(): List<Pair<Regex, String>> = PostgresHandler.POSTGRESQL_FORBIDDEN
}
