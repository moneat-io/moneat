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

class PostgresHandler(pools: ConcurrentHashMap<Long, com.zaxxer.hikari.HikariDataSource>) : JdbcHandler(
    driverClass = "org.postgresql.Driver",
    pools = pools,
) {
    override fun buildJdbcUrl(host: String, port: Int, database: String, options: ConnectionOptions): String =
        "jdbc:postgresql://$host:$port/$database" + JdbcHandlerCommon.postgresQuerySuffix(options)

    override fun defaultPort(): Int = 5432

    override fun defaultDatabase(): String = "postgres"

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

    override fun forbiddenKeywords(): List<Pair<Regex, String>> = POSTGRESQL_FORBIDDEN

    override fun forbiddenFunctionPatterns(): List<Pair<Regex, String>> = POSTGRESQL_FORBIDDEN_FUNCTIONS

    companion object {
        private fun wb(kw: String) = Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE)
        internal val POSTGRESQL_FORBIDDEN = listOf(
            wb("INSERT") to "INSERT",
            wb("UPDATE") to "UPDATE",
            wb("DELETE") to "DELETE",
            wb("DROP") to "DROP",
            wb("ALTER") to "ALTER",
            wb("CREATE") to "CREATE",
            wb("TRUNCATE") to "TRUNCATE",
            wb("GRANT") to "GRANT",
            wb("REVOKE") to "REVOKE",
            wb("EXEC") to "EXEC",
            wb("EXECUTE") to "EXECUTE",
            wb("COPY") to "COPY",
            wb("VACUUM") to "VACUUM",
            wb("ANALYZE") to "ANALYZE",
            wb("REINDEX") to "REINDEX",
            wb("CLUSTER") to "CLUSTER",
            Regex("""\bCOMMENT\s+ON\b""", RegexOption.IGNORE_CASE) to "COMMENT",
            Regex("""\bLOCK\s+TABLE\b""", RegexOption.IGNORE_CASE) to "LOCK",
        )

        private val PG_FUNCTIONS = listOf(
            "PG_READ_FILE", "PG_WRITE_FILE", "PG_READ_BINARY_FILE",
            "LO_IMPORT", "LO_EXPORT", "LO_CREATE", "LO_UNLINK",
            "PG_SLEEP", "PG_CANCEL_BACKEND", "PG_TERMINATE_BACKEND",
            "CURRENT_SETTING", "SET_CONFIG", "PG_RELOAD_CONF",
            "PG_ROTATE_LOGFILE", "DBLINK", "DBLINK_EXEC",
            "PG_SHADOW", "PG_AUTHID",
        )
        private val POSTGRESQL_FORBIDDEN_FUNCTIONS = PG_FUNCTIONS.map { fn ->
            Regex("""\b${Regex.escape(fn)}\s*\(""", RegexOption.IGNORE_CASE) to fn
        }
    }
}
