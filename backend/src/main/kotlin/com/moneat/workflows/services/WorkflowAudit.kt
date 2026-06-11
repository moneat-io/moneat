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

import com.moneat.shared.services.requireResourceId
import com.moneat.shared.services.userResourceIds
import com.moneat.workflows.models.WorkflowAuditEventResponse
import com.moneat.workflows.models.WorkflowAuditEvents
import com.moneat.workflows.models.workflowJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

/**
 * Append-only audit trail for the workflows surface. Records lifecycle and run
 * transitions so administrators can review who changed what and when. Callers
 * are expected to already run inside a transaction when wiring into service
 * methods; [record] opens its own transaction so it can also be called stand-alone.
 */
object WorkflowAudit {
    const val ACTION_CREATED = "created"
    const val ACTION_UPDATED = "updated"
    const val ACTION_PUBLISHED = "published"
    const val ACTION_UNPUBLISHED = "unpublished"
    const val ACTION_DELETED = "deleted"
    const val ACTION_RUN_STARTED = "run_started"
    const val ACTION_RUN_COMPLETED = "run_completed"
    const val ACTION_RUN_FAILED = "run_failed"
    const val ACTION_RUN_REFUSED = "run_refused"
    const val ACTION_INSTANTIATED_BLUEPRINT = "instantiated_blueprint"
    const val ACTION_IMPORTED = "imported"
    const val ACTION_EXPORTED = "exported"

    private const val DEFAULT_AUDIT_LIMIT = 100

    private val json = workflowJson

    fun record(
        organizationId: Int,
        action: String,
        workflowId: Int? = null,
        runId: Int? = null,
        actorUserId: Int? = null,
        detail: Map<String, String> = emptyMap()
    ) {
        transaction {
            insertEvent(organizationId, action, workflowId, runId, actorUserId, detail)
        }
    }

    /**
     * Inserts an audit event using the surrounding transaction. Use when the
     * caller already manages the transaction boundary (for example a service
     * method that persists the workflow and the audit row atomically).
     */
    fun recordInTransaction(
        organizationId: Int,
        action: String,
        workflowId: Int? = null,
        runId: Int? = null,
        actorUserId: Int? = null,
        detail: Map<String, String> = emptyMap()
    ) {
        insertEvent(organizationId, action, workflowId, runId, actorUserId, detail)
    }

    fun list(
        organizationId: Int,
        workflowId: Int? = null,
        limit: Int = DEFAULT_AUDIT_LIMIT
    ): List<WorkflowAuditEventResponse> =
        transaction {
            val rows = WorkflowAuditEvents
                .selectAll()
                .where {
                    if (workflowId == null) {
                        WorkflowAuditEvents.organizationId eq organizationId
                    } else {
                        (WorkflowAuditEvents.organizationId eq organizationId) and
                            (WorkflowAuditEvents.workflowId eq workflowId)
                    }
                }
                .orderBy(WorkflowAuditEvents.createdAt to SortOrder.DESC)
                .limit(limit)
                .toList()
            val workflowResourceIds = workflowResourceIds(rows.mapNotNull { it[WorkflowAuditEvents.workflowId] })
            val runResourceIds = workflowRunResourceIds(rows.mapNotNull { it[WorkflowAuditEvents.runId] })
            val userResourceIds = userResourceIds(rows.mapNotNull { it[WorkflowAuditEvents.actorUserId] })
            rows.map { row ->
                val rowWorkflowId = row[WorkflowAuditEvents.workflowId]
                val rowRunId = row[WorkflowAuditEvents.runId]
                val actorUserId = row[WorkflowAuditEvents.actorUserId]
                WorkflowAuditEventResponse(
                    id = row[WorkflowAuditEvents.resourceId].toString(),
                    workflowId = rowWorkflowId?.let { workflowResourceIds.requireResourceId(it, "workflow") },
                    runId = rowRunId?.let { runResourceIds.requireResourceId(it, "workflow run") },
                    action = row[WorkflowAuditEvents.action],
                    actorUserId = actorUserId?.let { userResourceIds.requireResourceId(it, "user") },
                    detail = decodeDetail(row[WorkflowAuditEvents.detail]),
                    createdAt = row[WorkflowAuditEvents.createdAt].toString()
                )
            }
        }

    private fun insertEvent(
        organizationId: Int,
        action: String,
        workflowId: Int?,
        runId: Int?,
        actorUserId: Int?,
        detail: Map<String, String>
    ) {
        WorkflowAuditEvents.insert {
            it[WorkflowAuditEvents.organizationId] = organizationId
            it[WorkflowAuditEvents.workflowId] = workflowId
            it[WorkflowAuditEvents.runId] = runId
            it[WorkflowAuditEvents.action] = action
            it[WorkflowAuditEvents.actorUserId] = actorUserId
            it[WorkflowAuditEvents.detail] = json.encodeToString(detail)
            it[createdAt] = Clock.System.now()
        }
    }

    private fun decodeDetail(raw: String): Map<String, String> =
        if (raw.isBlank()) emptyMap() else json.decodeFromString(raw)
}
