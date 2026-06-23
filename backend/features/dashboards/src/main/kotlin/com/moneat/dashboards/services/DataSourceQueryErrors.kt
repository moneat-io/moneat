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

package com.moneat.dashboards.services

import java.sql.SQLException

object DataSourceQueryErrors {
    private const val QUERY_ERROR_DETAIL_MAX_LENGTH = 500
    private const val TRUNCATION_SUFFIX = "..."
    private const val TRUNCATION_SUFFIX_LENGTH = 3
    private const val ERR_DATA_SOURCE_QUERY_FAILED = "Data source query failed"

    fun message(cause: SQLException): String {
        val detail = cause.message
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
            ?: cause.sqlState?.let { "SQL state $it" }
            ?: "database rejected the query"
        val truncated = if (detail.length > QUERY_ERROR_DETAIL_MAX_LENGTH) {
            detail.take(QUERY_ERROR_DETAIL_MAX_LENGTH - TRUNCATION_SUFFIX_LENGTH) + TRUNCATION_SUFFIX
        } else {
            detail
        }
        return "$ERR_DATA_SOURCE_QUERY_FAILED: $truncated"
    }
}
