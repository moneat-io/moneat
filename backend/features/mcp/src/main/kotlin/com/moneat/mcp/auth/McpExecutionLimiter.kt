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

package com.moneat.mcp.auth

import com.moneat.config.EnvConfig
import com.moneat.mcp.models.McpContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_MAX_CONCURRENT_TOOL_CALLS = 4

object McpExecutionLimiter {
    private val maxConcurrentCalls: Int by lazy {
        EnvConfig.get("MCP_MAX_CONCURRENT_TOOL_CALLS", DEFAULT_MAX_CONCURRENT_TOOL_CALLS.toString())
            .toIntOrNull()
            ?.coerceAtLeast(1)
            ?: DEFAULT_MAX_CONCURRENT_TOOL_CALLS
    }

    private val buckets = ConcurrentHashMap<String, PermitBucket>()

    suspend fun <T> withPermit(context: McpContext, block: suspend () -> T): T {
        val key = keyFor(context)
        val bucket = buckets.computeIfAbsent(key) {
            PermitBucket(Semaphore(maxConcurrentCalls))
        }
        bucket.users.incrementAndGet()
        return try {
            bucket.semaphore.withPermit { block() }
        } finally {
            if (bucket.users.decrementAndGet() == 0) {
                buckets.computeIfPresent(key) { _, current ->
                    current.takeIf { it !== bucket || it.users.get() > 0 }
                }
            }
        }
    }

    fun releaseContext(context: McpContext) {
        buckets.remove(keyFor(context))
    }

    private fun keyFor(context: McpContext): String = "${context.tokenId}:${context.sessionId}"

    private data class PermitBucket(
        val semaphore: Semaphore,
        val users: AtomicInteger = AtomicInteger(),
    )
}
