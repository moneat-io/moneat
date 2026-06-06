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

package com.moneat.utils

data class ClickHouseQuerySpec(
    val sql: String,
    val parameters: Map<String, String> = emptyMap(),
)

class ClickHouseQueryParameters {
    private var nextIndex = 0
    private val values = linkedMapOf<String, String>()

    fun string(value: String): String {
        val name = "p${nextIndex++}"
        values[name] = value
        return "{$name:String}"
    }

    fun asMap(): Map<String, String> = values.toMap()
}
