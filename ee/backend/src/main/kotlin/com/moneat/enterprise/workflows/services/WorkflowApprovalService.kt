// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.services

import com.moneat.enterprise.workflows.models.ApprovalResponse
import com.moneat.enterprise.workflows.models.WorkflowApprovals
import com.moneat.shared.services.requireResourceId
import com.moneat.shared.services.userResourceIds
import com.moneat.workflows.WorkflowApprovalBridge
import com.moneat.workflows.engine.temporal.TemporalClientProvider
import com.moneat.workflows.engine.temporal.WorkflowApprovalRequestInput
import com.moneat.workflows.engine.temporal.WorkflowApprovalRequestResult
import com.moneat.workflows.engine.temporal.WorkflowApprovalSignal
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.services.requireWorkflowResourceId
import com.moneat.workflows.services.workflowResourceIds
import com.moneat.workflows.services.workflowRunResourceIds
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val STATUS_PENDING = "pending"
private const val STATUS_APPROVED = "approved"
private const val STATUS_REJECTED = "rejected"

class WorkflowApprovalService(
    private val temporalClientProvider: () -> TemporalClientProvider = { TemporalClientProvider() }
) : WorkflowApprovalBridge {

    override suspend fun requestApproval(input: WorkflowApprovalRequestInput): WorkflowApprovalRequestResult {
        val approvalId =
            transaction {
                existingApprovalId(input.runId, input.nodeId)
                    ?: WorkflowApprovals.insertAndGetId {
                        it[organizationId] = input.organizationId
                        it[workflowId] = input.workflowId
                        it[runId] = input.runId
                        it[nodeId] = input.nodeId
                        it[message] = input.message
                        it[approverRole] = input.approverRole
                        it[status] = STATUS_PENDING
                        it[requestedAt] = Clock.System.now()
                    }.value
            }
        return WorkflowApprovalRequestResult(status = "complete", approvalId = approvalId)
    }

    fun listPending(organizationId: Int): List<ApprovalResponse> =
        transaction {
            val rows = WorkflowApprovals
                .selectAll()
                .where {
                    (WorkflowApprovals.organizationId eq organizationId) and
                        (WorkflowApprovals.status eq STATUS_PENDING)
                }
                .orderBy(WorkflowApprovals.requestedAt to SortOrder.DESC)
                .toList()
            val workflowResourceIds = workflowResourceIds(rows.map { row -> row[WorkflowApprovals.workflowId] })
            val runResourceIds = workflowRunResourceIds(rows.map { row -> row[WorkflowApprovals.runId] })
            rows.map { row -> row.toResponse(workflowResourceIds, runResourceIds) }
        }

    fun respond(
        organizationId: Int,
        approvalId: Int,
        approved: Boolean,
        actorUserId: Int,
        comment: String?
    ): ApprovalResponse? {
        val signalTarget =
            transaction {
                val approval =
                    WorkflowApprovals
                        .selectAll()
                        .where {
                            (WorkflowApprovals.id eq approvalId) and
                                (WorkflowApprovals.organizationId eq organizationId)
                        }
                        .firstOrNull() ?: return@transaction null
                if (approval[WorkflowApprovals.status] != STATUS_PENDING) {
                    return@transaction approval.toSignalTarget()
                }
                val status = if (approved) STATUS_APPROVED else STATUS_REJECTED
                WorkflowApprovals.update({ WorkflowApprovals.id eq approvalId }) {
                    it[WorkflowApprovals.status] = status
                    it[respondedAt] = Clock.System.now()
                    it[respondedBy] = actorUserId
                    it[WorkflowApprovals.comment] = comment
                }
                approval.toSignalTarget(approved)
            } ?: return null
        signalApproval(signalTarget, actorUserId, comment)
        return get(organizationId, approvalId)
    }

    fun get(
        organizationId: Int,
        approvalId: Int
    ): ApprovalResponse? =
        transaction {
            WorkflowApprovals
                .selectAll()
                .where {
                    (WorkflowApprovals.id eq approvalId) and
                        (WorkflowApprovals.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.toResponse()
        }

    private fun signalApproval(
        target: ApprovalSignalTarget,
        actorUserId: Int,
        comment: String?
    ) {
        val temporalWorkflowId = target.temporalWorkflowId ?: return
        temporalClientProvider().use { provider ->
            provider.client
                .newUntypedWorkflowStub(temporalWorkflowId)
                .signal(
                    "approve",
                    WorkflowApprovalSignal(
                        nodeId = target.nodeId,
                        approvalId = target.approvalId,
                        approved = target.approved,
                        actorUserId = actorUserId,
                        comment = comment
                    )
                )
        }
    }

    private fun existingApprovalId(
        runId: Int,
        nodeId: String
    ): Int? =
        WorkflowApprovals
            .selectAll()
            .where { (WorkflowApprovals.runId eq runId) and (WorkflowApprovals.nodeId eq nodeId) }
            .firstOrNull()
            ?.get(WorkflowApprovals.id)
            ?.value

    private fun ResultRow.toSignalTarget(approved: Boolean? = null): ApprovalSignalTarget {
        val runId = this[WorkflowApprovals.runId]
        val temporalWorkflowId =
            WorkflowRuns
                .selectAll()
                .where { WorkflowRuns.id eq runId }
                .firstOrNull()
                ?.get(WorkflowRuns.temporalWorkflowId)
        return ApprovalSignalTarget(
            approvalId = this[WorkflowApprovals.id].value,
            nodeId = this[WorkflowApprovals.nodeId],
            temporalWorkflowId = temporalWorkflowId,
            approved = approved ?: (this[WorkflowApprovals.status] == STATUS_APPROVED)
        )
    }

    private fun ResultRow.toResponse(
        workflowResourceIds: Map<Int, String> = workflowResourceIds(listOf(this[WorkflowApprovals.workflowId])),
        runResourceIds: Map<Int, String> = workflowRunResourceIds(listOf(this[WorkflowApprovals.runId])),
        responderResourceIds: Map<Int, String> = userResourceIds(listOfNotNull(this[WorkflowApprovals.respondedBy])),
    ): ApprovalResponse =
        ApprovalResponse(
            id = this[WorkflowApprovals.resourceId].toString(),
            workflowId = workflowResourceIds.requireWorkflowResourceId(this[WorkflowApprovals.workflowId], "workflow"),
            runId = runResourceIds.requireWorkflowResourceId(this[WorkflowApprovals.runId], "workflow run"),
            nodeId = this[WorkflowApprovals.nodeId],
            message = this[WorkflowApprovals.message],
            approverRole = this[WorkflowApprovals.approverRole],
            status = this[WorkflowApprovals.status],
            requestedAt = this[WorkflowApprovals.requestedAt].toString(),
            respondedAt = this[WorkflowApprovals.respondedAt]?.toString(),
            respondedBy = this[WorkflowApprovals.respondedBy]?.let {
                responderResourceIds.requireResourceId(it, "user")
            },
            comment = this[WorkflowApprovals.comment]
        )

    private data class ApprovalSignalTarget(
        val approvalId: Int,
        val nodeId: String,
        val temporalWorkflowId: String?,
        val approved: Boolean
    )
}
