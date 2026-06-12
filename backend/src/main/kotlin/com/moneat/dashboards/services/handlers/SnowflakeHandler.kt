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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Snowflake handler using JDBC.
 * Host should be the account identifier (e.g. xy12345.us-east-1) or full account.
 */
class SnowflakeHandler(pools: ConcurrentHashMap<Long, com.zaxxer.hikari.HikariDataSource>) : JdbcHandler(
    driverClass = "net.snowflake.client.jdbc.SnowflakeDriver",
    pools = pools,
) {
    override fun buildJdbcUrl(host: String, port: Int, database: String, options: ConnectionOptions): String {
        val account = host.removePrefix("https://").removePrefix("http://")
            .replace(".snowflakecomputing.com", "").trim()
        val params = buildList {
            if (database.isNotBlank()) add("db=${encodeParam(database)}")
            options.warehouse?.let { add("warehouse=${encodeParam(it)}") }
            options.role?.let { add("role=${encodeParam(it)}") }
            options.schema?.let { add("schema=${encodeParam(it)}") }
        }
        return if (params.isNotEmpty()) {
            "jdbc:snowflake://$account.snowflakecomputing.com/?" + params.joinToString("&")
        } else {
            "jdbc:snowflake://$account.snowflakecomputing.com/"
        }
    }

    override fun defaultPort(): Int = 443

    private fun encodeParam(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    override fun defaultDatabase(): String = ""

    override fun schemaIntrospectionQuery(): String = """
        SELECT table_name || '.' || column_name, data_type, ''
        FROM information_schema.columns
        WHERE table_catalog = CURRENT_DATABASE()
          AND table_schema = COALESCE(CURRENT_SCHEMA(), 'PUBLIC')
        ORDER BY table_name, ordinal_position
        LIMIT 500
    """.trimIndent()

    override fun testConnectionQuery(): String = """
        SELECT table_name FROM information_schema.tables
        WHERE table_catalog = CURRENT_DATABASE()
          AND table_schema = COALESCE(CURRENT_SCHEMA(), 'PUBLIC')
        ORDER BY table_name
        LIMIT 1
    """.trimIndent()

    override fun forbiddenKeywords(): List<Pair<Regex, String>> = listOf(
        Regex("""\bINSERT\b""", RegexOption.IGNORE_CASE) to "INSERT",
        Regex("""\bUPDATE\b""", RegexOption.IGNORE_CASE) to "UPDATE",
        Regex("""\bDELETE\b""", RegexOption.IGNORE_CASE) to "DELETE",
        Regex("""\bDROP\b""", RegexOption.IGNORE_CASE) to "DROP",
        Regex("""\bALTER\b""", RegexOption.IGNORE_CASE) to "ALTER",
        Regex("""\bCREATE\b""", RegexOption.IGNORE_CASE) to "CREATE",
        Regex("""\bTRUNCATE\b""", RegexOption.IGNORE_CASE) to "TRUNCATE",
    )
}
