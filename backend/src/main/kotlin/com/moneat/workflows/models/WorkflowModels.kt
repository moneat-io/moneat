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

object Workflows : IntIdTable("workflows") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val triggerName = varchar("trigger_name", 120)
    val enabled = bool("enabled").default(true)
    val systemKey = varchar("system_key", 120).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object WorkflowVersions : IntIdTable("workflow_versions") {
    val workflowId = integer("workflow_id").references(Workflows.id, onDelete = ReferenceOption.CASCADE)
    val version = integer("version")
    val conditions = jsonb("conditions").default("[]")
    val steps = jsonb("steps").default("[]")
    val onceForTemplate = jsonb("once_for_template").default("[]")
    val engineConfig = jsonb("engine_config").default("{}")
    val mostRecent = bool("most_recent").default(true)
    val createdAt = timestamp("created_at")
}

object WorkflowRuns : IntIdTable("workflow_runs") {
    val workflowId = integer("workflow_id").references(Workflows.id, onDelete = ReferenceOption.CASCADE)
    val workflowVersionId = integer("workflow_version_id").references(WorkflowVersions.id)
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val triggerName = varchar("trigger_name", 120)
    val onceFor = text("once_for")
    val scope = jsonb("scope").default("{}")
    val status = varchar("status", 32).default("pending")
    val progress = jsonb("progress").default("[]")
    val errorMessage = text("error_message").nullable()
    val createdAt = timestamp("created_at")
    val completedAt = timestamp("completed_at").nullable()
    val failedAt = timestamp("failed_at").nullable()
}

@Serializable
data class WorkflowConditionConfig(
    val reference: String,
    val operation: String,
    val value: String? = null
)

@Serializable
data class WorkflowStepConfig(
    val name: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class WorkflowRunStepProgress(
    val step: String,
    val status: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("error_message") val errorMessage: String? = null
)

@Serializable
data class CreateWorkflowRequest(
    val name: String,
    @SerialName("trigger_name") val triggerName: String,
    val enabled: Boolean = true,
    val conditions: List<WorkflowConditionConfig> = emptyList(),
    val steps: List<WorkflowStepConfig> = emptyList(),
    @SerialName("once_for_template") val onceForTemplate: List<String> = emptyList()
)

@Serializable
data class UpdateWorkflowRequest(
    val name: String? = null,
    val enabled: Boolean? = null,
    val conditions: List<WorkflowConditionConfig>? = null,
    val steps: List<WorkflowStepConfig>? = null,
    @SerialName("once_for_template") val onceForTemplate: List<String>? = null
)

@Serializable
data class WorkflowResponse(
    val id: Int,
    val name: String,
    @SerialName("trigger_name") val triggerName: String,
    val enabled: Boolean,
    val version: Int,
    val conditions: List<WorkflowConditionConfig>,
    val steps: List<WorkflowStepConfig>,
    @SerialName("once_for_template") val onceForTemplate: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("run_count") val runCount: Long = 0
)

@Serializable
data class WorkflowRunResponse(
    val id: Int,
    @SerialName("workflow_id") val workflowId: Int,
    @SerialName("workflow_version_id") val workflowVersionId: Int,
    @SerialName("trigger_name") val triggerName: String,
    @SerialName("once_for") val onceFor: String,
    val status: String,
    val progress: List<WorkflowRunStepProgress>,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("failed_at") val failedAt: String? = null
)

@Serializable
data class WorkflowTriggerEvent(
    @SerialName("trigger_name") val triggerName: String,
    @SerialName("organization_id") val organizationId: Int,
    val scope: Map<String, String>
)

@Serializable
data class WorkflowRunQueuedMessage(
    @SerialName("run_id") val runId: Int
)
