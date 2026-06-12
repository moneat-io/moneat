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

package com.moneat.config

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object ClickHouseInsertDeduplication {
    private val tokenSeed = ThreadLocal<String?>()

    suspend fun <T> withTokenSeed(
        seed: String?,
        block: suspend () -> T,
    ): T {
        if (seed.isNullOrBlank()) return block()
        return withContext(tokenSeed.asContextElement(seed)) {
            block()
        }
    }

    fun tokenForQuery(query: String): String? {
        val seed = tokenSeed.get()?.takeIf { it.isNotBlank() } ?: return null
        return sha256Hex("$seed\n${query.trim()}")
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
