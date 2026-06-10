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

package com.moneat.logs.repositories

/**
 * Repository for log data access.
 * Abstracts ClickHouse log queries and PostgreSQL (LogIndexes, OtlpApiKeys).
 */
interface LogRepository {
    suspend fun executeClickHouseInsert(sql: String): Boolean
    suspend fun executeClickHouseQuery(sql: String): String
    suspend fun executeClickHouseQuery(
        sql: String,
        queryParameters: Map<String, String>
    ): String = executeClickHouseQuery(sql)
}
