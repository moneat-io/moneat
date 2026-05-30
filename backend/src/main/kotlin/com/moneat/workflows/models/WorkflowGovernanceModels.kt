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

package com.moneat.workflows.models

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.jsonb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

object WorkflowAuditEvents : IntIdTable("workflow_audit_events") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val workflowId = integer("workflow_id").references(Workflows.id, onDelete = ReferenceOption.CASCADE).nullable()
    val runId = integer("run_id").nullable()
    val action = varchar("action", 48)
    val actorUserId = integer("actor_user_id").nullable()
    val detail = jsonb("detail").default("{}")
    val createdAt = timestamp("created_at")
}

object WorkflowUsageEvents : IntIdTable("workflow_usage_events") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val workflowId = integer("workflow_id").nullable()
    val runId = integer("run_id")
    val period = varchar("period", 7)
    val outcome = varchar("outcome", 16)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_workflow_usage_events_run_outcome", runId, outcome)
    }
}

@Serializable
data class WorkflowAuditEventResponse(
    val id: Int,
    @SerialName("workflow_id") val workflowId: Int? = null,
    @SerialName("run_id") val runId: Int? = null,
    val action: String,
    @SerialName("actor_user_id") val actorUserId: Int? = null,
    val detail: Map<String, String> = emptyMap(),
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class WorkflowBlueprintSummary(
    val key: String,
    val name: String,
    val description: String,
    val category: String,
    @SerialName("trigger_name") val triggerName: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class WorkflowBlueprintDetail(
    val key: String,
    val name: String,
    val description: String,
    val category: String,
    @SerialName("trigger_name") val triggerName: String,
    val tags: List<String> = emptyList(),
    val conditions: List<WorkflowConditionConfig> = emptyList(),
    val steps: List<WorkflowStepConfig> = emptyList(),
    val graph: WorkflowGraphConfig,
    @SerialName("once_for_template") val onceForTemplate: List<String> = emptyList()
)

@Serializable
data class InstantiateBlueprintRequest(
    val name: String? = null
)

@Serializable
data class WorkflowOverviewTopWorkflow(
    @SerialName("workflow_id") val workflowId: Int,
    val name: String,
    @SerialName("run_count") val runCount: Long
)

@Serializable
data class WorkflowOverviewResponse(
    @SerialName("total_workflows") val totalWorkflows: Long,
    @SerialName("enabled_workflows") val enabledWorkflows: Long,
    @SerialName("published_workflows") val publishedWorkflows: Long,
    @SerialName("runs_last_30d") val runsLast30d: Long,
    @SerialName("success_rate") val successRate: Double,
    @SerialName("failed_last_30d") val failedLast30d: Long,
    @SerialName("top_workflows") val topWorkflows: List<WorkflowOverviewTopWorkflow> = emptyList()
)

@Serializable
data class WorkflowUsageResponse(
    val period: String,
    val used: Long,
    val limit: Int? = null,
    val remaining: Int? = null,
    val unlimited: Boolean
)

@Serializable
data class WorkflowGraphResource(
    val name: String,
    @SerialName("trigger_name") val triggerName: String,
    val enabled: Boolean,
    val graph: WorkflowGraphConfig,
    @SerialName("once_for_template") val onceForTemplate: List<String> = emptyList()
)

@Serializable
data class WorkflowExportResponse(
    @SerialName("schema_version") val schemaVersion: Int,
    val resource: WorkflowGraphResource,
    val terraform: String
)

@Serializable
data class WorkflowImportRequest(
    val name: String,
    @SerialName("trigger_name") val triggerName: String,
    val graph: WorkflowGraphConfig,
    val enabled: Boolean = false,
    @SerialName("once_for_template") val onceForTemplate: List<String> = emptyList()
)
