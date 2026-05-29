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
import kotlinx.serialization.json.JsonElement
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
    val graph = jsonb("graph").default("""{"nodes":[],"edges":[]}""")
    val published = bool("published").default(false)
    val inputSchema = jsonb("input_schema").default("{}")
    val tags = jsonb("tags").default("[]")
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
    val temporalWorkflowId = varchar("temporal_workflow_id", 255).nullable()
    val temporalRunId = varchar("temporal_run_id", 255).nullable()
    val createdAt = timestamp("created_at")
    val completedAt = timestamp("completed_at").nullable()
    val failedAt = timestamp("failed_at").nullable()
}

object WorkflowRunSteps : IntIdTable("workflow_run_steps") {
    val runId = integer("run_id").references(WorkflowRuns.id, onDelete = ReferenceOption.CASCADE)
    val nodeId = varchar("node_id", 120)
    val type = varchar("type", 64)
    val status = varchar("status", 32)
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val input = jsonb("input").default("{}")
    val output = jsonb("output").default("{}")
    val errorMessage = text("error_message").nullable()
    val attempt = integer("attempt").default(1)
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
data class WorkflowRetryConfig(
    @SerialName("max_attempts") val maxAttempts: Int = 1,
    @SerialName("initial_interval") val initialInterval: String = "PT1S",
    @SerialName("backoff_coefficient") val backoffCoefficient: Double = 2.0,
    @SerialName("maximum_interval") val maximumInterval: String? = null,
    @SerialName("non_retryable_error_types") val nonRetryableErrorTypes: List<String> = emptyList()
)

@Serializable
data class WorkflowSwitchCaseConfig(
    val name: String,
    val label: String? = null,
    val conditions: List<WorkflowConditionConfig> = emptyList()
)

@Serializable
data class WorkflowGraphNode(
    val id: String,
    val type: String,
    val trigger: String? = null,
    val kind: String? = null,
    val action: String? = null,
    val params: Map<String, JsonElement> = emptyMap(),
    val conditions: List<WorkflowConditionConfig> = emptyList(),
    val cases: List<WorkflowSwitchCaseConfig> = emptyList(),
    val retry: WorkflowRetryConfig? = null,
    @SerialName("continue_on_error") val continueOnError: Boolean = false
)

@Serializable
data class WorkflowGraphEdge(
    val from: String,
    val to: String,
    val branch: String? = null,
    val on: String? = null
)

@Serializable
data class WorkflowGraphConfig(
    val nodes: List<WorkflowGraphNode> = emptyList(),
    val edges: List<WorkflowGraphEdge> = emptyList()
)

@Serializable
data class WorkflowPreviewRequest(
    @SerialName("trigger_name") val triggerName: String,
    val steps: List<WorkflowStepConfig> = emptyList(),
    val scope: Map<String, JsonElement> = emptyMap(),
    val graph: WorkflowGraphConfig? = null,
    @SerialName("node_id") val nodeId: String? = null
)

@Serializable
data class WorkflowPreviewResponse(
    val scope: Map<String, String>,
    val previews: List<WorkflowStepPreview>
)

@Serializable
data class WorkflowTestMessageResponse(
    val scope: Map<String, String>,
    val results: List<WorkflowTestMessageResult>
)

@Serializable
data class WorkflowTestMessageResult(
    val step: String,
    val channel: String,
    val status: String,
    @SerialName("error_message") val errorMessage: String? = null
)

@Serializable
data class WorkflowStepPreview(
    val step: String,
    val channel: String,
    val title: String,
    val subject: String? = null,
    val body: String,
    @SerialName("html_body") val htmlBody: String? = null,
    @SerialName("text_body") val textBody: String,
    val fields: List<WorkflowPreviewField> = emptyList(),
    val color: String,
    @SerialName("cta_label") val ctaLabel: String? = null,
    @SerialName("cta_url") val ctaUrl: String? = null,
    val footer: String? = null,
    @SerialName("fallback_text") val fallbackText: String
)

@Serializable
data class WorkflowPreviewField(
    val label: String,
    val value: String
)

@Serializable
data class WorkflowRunStepProgress(
    val step: String,
    val status: String,
    @SerialName("node_id") val nodeId: String? = null,
    val type: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val output: Map<String, JsonElement> = emptyMap(),
    @SerialName("error_message") val errorMessage: String? = null
)

@Serializable
data class CreateWorkflowRequest(
    val name: String,
    @SerialName("trigger_name") val triggerName: String,
    val enabled: Boolean = true,
    val conditions: List<WorkflowConditionConfig> = emptyList(),
    val steps: List<WorkflowStepConfig> = emptyList(),
    val graph: WorkflowGraphConfig? = null,
    @SerialName("once_for_template") val onceForTemplate: List<String> = emptyList()
)

@Serializable
data class UpdateWorkflowRequest(
    val name: String? = null,
    val enabled: Boolean? = null,
    val conditions: List<WorkflowConditionConfig>? = null,
    val steps: List<WorkflowStepConfig>? = null,
    val graph: WorkflowGraphConfig? = null,
    @SerialName("once_for_template") val onceForTemplate: List<String>? = null
)

@Serializable
data class ManualWorkflowRunRequest(
    val scope: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class WorkflowResponse(
    val id: Int,
    val name: String,
    @SerialName("trigger_name") val triggerName: String,
    val enabled: Boolean,
    val version: Int,
    val published: Boolean,
    @SerialName("system_key") val systemKey: String? = null,
    val conditions: List<WorkflowConditionConfig>,
    val steps: List<WorkflowStepConfig>,
    val graph: WorkflowGraphConfig,
    @SerialName("once_for_template") val onceForTemplate: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("run_count") val runCount: Long = 0
)

@Serializable
data class WorkflowRunStepResponse(
    val id: Int,
    @SerialName("run_id") val runId: Int,
    @SerialName("node_id") val nodeId: String,
    val type: String,
    val status: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val input: Map<String, JsonElement> = emptyMap(),
    val output: Map<String, JsonElement> = emptyMap(),
    @SerialName("error_message") val errorMessage: String? = null,
    val attempt: Int
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
    val steps: List<WorkflowRunStepResponse> = emptyList(),
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("failed_at") val failedAt: String? = null
)

@Serializable
data class WorkflowTriggerEvent(
    @SerialName("trigger_name") val triggerName: String,
    @SerialName("organization_id") val organizationId: Int,
    val scope: Map<String, JsonElement>
)
