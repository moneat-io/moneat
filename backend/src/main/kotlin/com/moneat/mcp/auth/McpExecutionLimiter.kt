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

private const val DEFAULT_MAX_CONCURRENT_TOOL_CALLS = 4

object McpExecutionLimiter {
    private val maxConcurrentCalls: Int by lazy {
        EnvConfig.get("MCP_MAX_CONCURRENT_TOOL_CALLS", DEFAULT_MAX_CONCURRENT_TOOL_CALLS.toString())
            .toIntOrNull()
            ?.coerceAtLeast(1)
            ?: DEFAULT_MAX_CONCURRENT_TOOL_CALLS
    }

    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> withPermit(context: McpContext, block: suspend () -> T): T {
        val key = "${context.tokenId}:${context.sessionId}"
        val semaphore = semaphores.computeIfAbsent(key) {
            Semaphore(maxConcurrentCalls)
        }
        return semaphore.withPermit { block() }
    }
}
