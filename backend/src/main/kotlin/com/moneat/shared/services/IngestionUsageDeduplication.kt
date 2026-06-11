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

package com.moneat.shared.services

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

object IngestionUsageDeduplication {
    private data class DeduplicationState(
        val seed: String,
        val nextSequence: AtomicInteger = AtomicInteger(0),
    )

    private val state = ThreadLocal<DeduplicationState?>()

    suspend fun <T> withSeed(
        value: String?,
        block: suspend () -> T,
    ): T {
        if (value.isNullOrBlank()) return block()
        return withContext(state.asContextElement(DeduplicationState(value))) {
            block()
        }
    }

    fun keyFor(
        organizationId: Int,
        projectId: Long,
        eventType: String,
        recordDate: kotlinx.datetime.LocalDate,
    ): String? {
        val activeState = state.get() ?: return null
        val sequence = activeState.nextSequence.incrementAndGet()
        return sha256Hex("${activeState.seed}|$sequence|$organizationId|$projectId|$eventType|$recordDate")
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
