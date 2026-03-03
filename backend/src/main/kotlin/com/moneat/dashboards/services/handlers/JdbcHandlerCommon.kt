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
}
