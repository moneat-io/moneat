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

package com.moneat.events.services

import com.moneat.utils.ClickHouseQueryUtils

data class ServiceQueryScope(
    val serviceIds: List<Long>
) {
    fun projectIdClause(columnName: String = "project_id"): String {
        if (serviceIds.isEmpty()) return EMPTY_SCOPE_CLAUSE
        if (serviceIds.size == 1) return ClickHouseQueryUtils.projectIdClause(serviceIds.first(), columnName)

        val values = serviceIds.joinToString(", ")
        return if (serviceIds.any { it < 0 }) {
            "toInt64($columnName) IN ($values)"
        } else {
            "$columnName IN ($values)"
        }
    }

    fun cacheKeyPart(): String =
        serviceIds.joinToString(",")

    companion object {
        private const val EMPTY_SCOPE_CLAUSE = "0 = 1"

        fun service(serviceId: Long): ServiceQueryScope =
            ServiceQueryScope(listOf(serviceId))

        fun services(serviceIds: List<Long>): ServiceQueryScope =
            ServiceQueryScope(serviceIds.distinct())
    }
}
