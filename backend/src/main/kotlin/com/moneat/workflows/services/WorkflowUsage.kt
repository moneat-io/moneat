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

import com.moneat.config.EnvConfig
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowUsageEvents
import com.moneat.workflows.models.WorkflowUsageResponse
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Monthly per-execution quota accounting. The ceiling is read from
 * `WORKFLOWS_MONTHLY_EXECUTION_LIMIT`; an absent or non-positive value means
 * unlimited. Completed executions are counted exactly once per run via the
 * `UNIQUE (run_id, outcome)` constraint on `workflow_usage_events`, so replays
 * and retries never inflate usage.
 */
object WorkflowUsage {
    const val OUTCOME_COMPLETED = "completed"
    const val OUTCOME_REFUSED = "refused"
    const val MONTHLY_LIMIT_ENV = "WORKFLOWS_MONTHLY_EXECUTION_LIMIT"

    private const val UNLIMITED = 0

    fun monthlyLimit(): Int =
        EnvConfig.get(MONTHLY_LIMIT_ENV)?.toIntOrNull()?.takeIf { it > 0 } ?: UNLIMITED

    fun period(now: Instant): String {
        val utc = now.toJavaInstant().atOffset(java.time.ZoneOffset.UTC)
        return "%04d-%02d".format(utc.year, utc.monthValue)
    }

    fun completedCount(
        organizationId: Int,
        period: String
    ): Long =
        transaction { countCompleted(organizationId, period) }

    fun isOverQuota(
        organizationId: Int,
        now: Instant
    ): Boolean {
        val limit = monthlyLimit()
        if (limit <= UNLIMITED) return false
        return completedCount(organizationId, period(now)) >= limit
    }

    /**
     * Records a completed execution for the run's organization. Idempotent: a
     * second call for the same run is ignored by the unique constraint. Returns
     * true when this call actually inserted the completion row.
     */
    fun recordCompleted(runId: Int): Boolean =
        transaction {
            val runRow =
                WorkflowRuns
                    .selectAll()
                    .where { WorkflowRuns.id eq runId }
                    .firstOrNull() ?: return@transaction false
            val organizationId = runRow[WorkflowRuns.organizationId]
            val workflowId = runRow[WorkflowRuns.workflowId]
            val period = period(runRow[WorkflowRuns.createdAt])
            insertUsageEvent(organizationId, workflowId, runId, period, OUTCOME_COMPLETED)
        }

    fun recordRefused(
        organizationId: Int,
        workflowId: Int?,
        now: Instant
    ) {
        // Refused executions have no run row, so run_id is always null; the unique
        // (run_id, outcome) constraint treats nulls as distinct, so each refusal
        // is recorded rather than deduplicated.
        transaction {
            insertUsageEvent(organizationId, workflowId, runId = null, period(now), OUTCOME_REFUSED)
        }
    }

    fun summary(
        organizationId: Int,
        now: Instant
    ): WorkflowUsageResponse {
        val limit = monthlyLimit()
        val period = period(now)
        val used = completedCount(organizationId, period)
        if (limit <= UNLIMITED) {
            return WorkflowUsageResponse(
                period = period,
                used = used,
                limit = null,
                remaining = null,
                unlimited = true
            )
        }
        val remaining = (limit - used).coerceAtLeast(0).toInt()
        return WorkflowUsageResponse(
            period = period,
            used = used,
            limit = limit,
            remaining = remaining,
            unlimited = false
        )
    }

    private fun countCompleted(
        organizationId: Int,
        period: String
    ): Long =
        WorkflowUsageEvents
            .selectAll()
            .where {
                (WorkflowUsageEvents.organizationId eq organizationId) and
                    (WorkflowUsageEvents.period eq period) and
                    (WorkflowUsageEvents.outcome eq OUTCOME_COMPLETED)
            }
            .count()

    private fun insertUsageEvent(
        organizationId: Int,
        workflowId: Int?,
        runId: Int?,
        period: String,
        outcome: String
    ): Boolean =
        WorkflowUsageEvents.insertIgnore {
            it[WorkflowUsageEvents.organizationId] = organizationId
            it[WorkflowUsageEvents.workflowId] = workflowId
            it[WorkflowUsageEvents.runId] = runId
            it[WorkflowUsageEvents.period] = period
            it[WorkflowUsageEvents.outcome] = outcome
            it[createdAt] = Clock.System.now()
        }.insertedCount > 0
}
