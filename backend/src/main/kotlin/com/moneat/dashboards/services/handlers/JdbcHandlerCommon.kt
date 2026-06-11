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

internal object JdbcHandlerCommon {
    fun wb(kw: String) = Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE)

    val JDBC_COMMON_FORBIDDEN = listOf(
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
    )

    /**
     * PostgreSQL / CockroachDB JDBC URL suffix. sslmode values map 1:1 to the
     * dialog's tls_mode (disable/require/verify-ca/verify-full); a blank mode emits
     * nothing so a local plaintext server keeps the driver default.
     */
    fun postgresQuerySuffix(options: ConnectionOptions): String {
        val params = buildList {
            options.tlsMode?.let { add("sslmode=$it") }
            options.schema?.let { add("currentSchema=$it") }
        }
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    /** MySQL Connector/J sslMode enum for a tls_mode, or null to leave the driver default. */
    fun mysqlSslMode(tlsMode: String?): String? = when (tlsMode) {
        "disable" -> "DISABLED"
        "require" -> "REQUIRED"
        "verify-ca" -> "VERIFY_CA"
        "verify-full" -> "VERIFY_IDENTITY"
        else -> null
    }

    /** MariaDB sslMode value for a tls_mode, or null to leave the driver default. */
    fun mariadbSslMode(tlsMode: String?): String? = when (tlsMode) {
        "disable" -> "disable"
        "require" -> "trust"
        "verify-ca" -> "verify-ca"
        "verify-full" -> "verify-full"
        else -> null
    }
}
