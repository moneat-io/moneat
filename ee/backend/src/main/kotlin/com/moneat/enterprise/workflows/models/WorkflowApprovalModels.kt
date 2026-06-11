// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

object WorkflowApprovals : IntIdTable("workflow_approvals") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id")
    val workflowId = integer("workflow_id")
    val runId = integer("run_id")
    val nodeId = varchar("node_id", 120)
    val message = text("message")
    val approverRole = varchar("approver_role", 64).nullable()
    val status = varchar("status", 32)
    val requestedAt = timestamp("requested_at")
    val respondedAt = timestamp("responded_at").nullable()
    val respondedBy = integer("responded_by").nullable()
    val comment = text("comment").nullable()

    init {
        uniqueIndex(runId, nodeId)
    }
}

@Serializable
data class ApprovalResponse(
    val id: String,
    @SerialName("workflow_id") val workflowId: String,
    @SerialName("run_id") val runId: String,
    @SerialName("node_id") val nodeId: String,
    val message: String,
    @SerialName("approver_role") val approverRole: String?,
    val status: String,
    @SerialName("requested_at") val requestedAt: String,
    @SerialName("responded_at") val respondedAt: String?,
    @SerialName("responded_by") val respondedBy: String?,
    val comment: String?
)

@Serializable
data class RespondApprovalRequest(
    val approved: Boolean,
    val comment: String? = null
)
