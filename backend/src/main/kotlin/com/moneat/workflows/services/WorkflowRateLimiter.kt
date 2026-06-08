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

package com.moneat.workflows.services

import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.workflowStringValue
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Per-workflow sliding-window rate limiter. The window length and ceiling are
 * read from the trigger node parameters `rate_limit_count` and
 * `rate_limit_interval_seconds`; an absent or non-positive value disables the
 * limit. A workflow is limited when the number of runs created within the window
 * already meets or exceeds the configured count.
 */
object WorkflowRateLimiter {
    const val RATE_LIMIT_COUNT_PARAM = "rate_limit_count"
    const val RATE_LIMIT_INTERVAL_SECONDS_PARAM = "rate_limit_interval_seconds"

    private const val NODE_TYPE_TRIGGER = "trigger"

    fun configFor(graph: WorkflowGraphConfig): WorkflowRateLimitConfig? {
        val triggerNode = graph.nodes.firstOrNull { node -> node.type == NODE_TYPE_TRIGGER } ?: return null
        val count = triggerNode.params[RATE_LIMIT_COUNT_PARAM].asIntParam() ?: return null
        val intervalSeconds = triggerNode.params[RATE_LIMIT_INTERVAL_SECONDS_PARAM].asIntParam() ?: return null
        if (count <= 0 || intervalSeconds <= 0) return null
        return WorkflowRateLimitConfig(count, intervalSeconds)
    }

    // Workflow params normalize numbers to their decimal form (for example "1" -> "1.0"),
    // so parse via Double before narrowing to Int to accept both shapes.
    private fun kotlinx.serialization.json.JsonElement?.asIntParam(): Int? {
        val raw = this?.workflowStringValue()?.takeIf { it.isNotBlank() } ?: return null
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
    }

    fun isLimited(
        workflowId: Int,
        count: Int,
        intervalSeconds: Int,
        now: Instant = Clock.System.now()
    ): Boolean {
        if (count <= 0 || intervalSeconds <= 0) return false
        val windowStart = now - intervalSeconds.seconds
        val runsInWindow =
            transaction {
                WorkflowRuns
                    .selectAll()
                    .where {
                        (WorkflowRuns.workflowId eq workflowId) and
                            (WorkflowRuns.createdAt greaterEq windowStart)
                    }
                    .count()
            }
        return runsInWindow >= count
    }

    fun isLimited(
        workflowId: Int,
        graph: WorkflowGraphConfig,
        now: Instant = Clock.System.now()
    ): Boolean {
        val config = configFor(graph) ?: return false
        return isLimited(workflowId, config.count, config.intervalSeconds, now)
    }
}

data class WorkflowRateLimitConfig(
    val count: Int,
    val intervalSeconds: Int
)
