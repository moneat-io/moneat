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

package com.moneat.workflows.engine.temporal

import com.moneat.config.EnvConfig
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphEdge
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowGraphPosition
import com.moneat.workflows.models.WorkflowRetryConfig
import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowSwitchCaseConfig
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.workflowJson
import com.moneat.workflows.models.workflowStringValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

const val WORKFLOW_TASK_QUEUE = "moneat-workflows-trusted"
const val WORKFLOW_EGRESS_TASK_QUEUE = "moneat-workflows-egress"
const val HTTP_REQUEST_ACTION = "http.request"
const val TRANSFORM_GRAALJS_ACTION = "transform.graaljs"
const val CONNECTOR_JIRA_CREATE_ISSUE_ACTION = "connector.jira.create_issue"
const val CONNECTOR_PAGERDUTY_TRIGGER_INCIDENT_ACTION = "connector.pagerduty.trigger_incident"
const val CONNECTOR_GITHUB_CREATE_ISSUE_ACTION = "connector.github.create_issue"
const val CONNECTOR_SERVICENOW_CREATE_INCIDENT_ACTION = "connector.servicenow.create_incident"
internal const val ORGANIZATION_SEARCH_ATTRIBUTE = "organizationId"
internal const val WORKFLOW_SEARCH_ATTRIBUTE = "workflowId"

val WORKFLOW_EGRESS_ACTIONS = setOf(HTTP_REQUEST_ACTION, TRANSFORM_GRAALJS_ACTION)
val WORKFLOW_PREMIUM_CONNECTOR_ACTIONS =
    setOf(
        CONNECTOR_JIRA_CREATE_ISSUE_ACTION,
        CONNECTOR_PAGERDUTY_TRIGGER_INCIDENT_ACTION,
        CONNECTOR_GITHUB_CREATE_ISSUE_ACTION,
        CONNECTOR_SERVICENOW_CREATE_INCIDENT_ACTION
    )

const val WORKFLOWS_EGRESS_ENABLED_ENV = "WORKFLOWS_EGRESS_ENABLED"

/**
 * Whether the `http.request` and `transform.graaljs` actions are enabled (default false).
 *
 * When disabled they are hidden from the catalog, rejected by graph validation, and refused
 * at execution time.
 */
fun workflowEgressActionsEnabled(): Boolean =
    EnvConfig.get(WORKFLOWS_EGRESS_ENABLED_ENV)?.toBoolean() ?: false

data class WorkflowStartRequest(
    val runId: Int,
    val workflowId: Int,
    val workflowVersionId: Int,
    val organizationId: Int,
    val triggerName: String,
    val onceFor: String,
    val temporalWorkflowId: String,
    val scope: Map<String, JsonElement>
)

data class WorkflowStartResult(
    val temporalWorkflowId: String,
    val temporalRunId: String?
)

data class WorkflowInterpreterInput(
    var runId: Int = 0,
    var workflowId: Int = 0,
    var workflowVersionId: Int = 0,
    var organizationId: Int = 0,
    var triggerName: String = ""
)

data class WorkflowInterpreterResult(
    var status: String = "",
    var progress: List<RuntimeWorkflowRunStepProgress> = emptyList(),
    var errorMessage: String? = null
)

data class ExecuteActionInput(
    var organizationId: Int = 0,
    var step: WorkflowStepConfig = WorkflowStepConfig(name = ""),
    var scope: Map<String, String> = emptyMap(),
    var runId: Int? = null,
    var nodeId: String? = null,
)

data class ExecuteActionResult(
    var status: String = "",
    var completedAt: String = "",
    var output: Map<String, String> = emptyMap(),
    var errorMessage: String? = null
)

data class WorkflowApprovalRequestInput(
    var runId: Int = 0,
    var workflowId: Int = 0,
    var organizationId: Int = 0,
    var nodeId: String = "",
    var message: String = "",
    var approverRole: String? = null,
    var timeout: String = ""
)

data class WorkflowApprovalRequestResult(
    var status: String = "",
    var approvalId: Int? = null,
    var errorMessage: String? = null
)

data class WorkflowApprovalSignal(
    var nodeId: String = "",
    var approvalId: Int? = null,
    var approved: Boolean = false,
    var actorUserId: Int? = null,
    var comment: String? = null
)

data class LoadRunInput(
    var runId: Int = 0
)

data class WorkflowRunExecutionSnapshot(
    var runId: Int = 0,
    var organizationId: Int = 0,
    var workflowVersionId: Int = 0,
    var triggerName: String = "",
    var steps: List<WorkflowStepConfig> = emptyList(),
    var graph: RuntimeWorkflowGraphConfig = RuntimeWorkflowGraphConfig(),
    var progress: List<RuntimeWorkflowRunStepProgress> = emptyList(),
    var scope: Map<String, String> = emptyMap()
)

data class PersistRunProgressInput(
    var runId: Int = 0,
    var status: String = "",
    var progress: List<RuntimeWorkflowRunStepProgress> = emptyList()
)

data class PersistRunFailureInput(
    var runId: Int = 0,
    var progress: List<RuntimeWorkflowRunStepProgress> = emptyList(),
    var errorMessage: String = ""
)

data class PersistRunStepInput(
    var runId: Int = 0,
    var nodeId: String = "",
    var type: String = "",
    var status: String = "",
    var input: Map<String, String> = emptyMap(),
    var output: Map<String, String> = emptyMap(),
    var errorMessage: String? = null,
    var attempt: Int = 1
)

data class RuntimeWorkflowGraphConfig(
    var nodes: List<RuntimeWorkflowGraphNode> = emptyList(),
    var edges: List<RuntimeWorkflowGraphEdge> = emptyList()
)

data class RuntimeWorkflowGraphNode(
    var id: String = "",
    var type: String = "",
    var trigger: String? = null,
    var kind: String? = null,
    var action: String? = null,
    var params: Map<String, String> = emptyMap(),
    var conditions: List<WorkflowConditionConfig> = emptyList(),
    var cases: List<WorkflowSwitchCaseConfig> = emptyList(),
    var retry: WorkflowRetryConfig? = null,
    var continueOnError: Boolean = false,
    var position: WorkflowGraphPosition? = null
)

data class RuntimeWorkflowGraphEdge(
    var from: String = "",
    var to: String = "",
    var branch: String? = null,
    var on: String? = null
)

data class RuntimeWorkflowRunStepProgress(
    var step: String = "",
    var status: String = "",
    var nodeId: String? = null,
    var type: String? = null,
    var completedAt: String? = null,
    var output: Map<String, String> = emptyMap(),
    var errorMessage: String? = null
)

fun WorkflowGraphConfig.toRuntimeGraph(): RuntimeWorkflowGraphConfig =
    RuntimeWorkflowGraphConfig(
        nodes = nodes.map { node -> node.toRuntimeGraphNode() },
        edges = edges.map { edge ->
            RuntimeWorkflowGraphEdge(edge.from, edge.to, edge.branch, edge.on)
        }
    )

fun RuntimeWorkflowGraphConfig.toWorkflowGraphConfig(): WorkflowGraphConfig =
    WorkflowGraphConfig(
        nodes = nodes.map { node -> node.toWorkflowGraphNode() },
        edges = edges.map { edge ->
            WorkflowGraphEdge(edge.from, edge.to, edge.branch, edge.on)
        }
    )

fun Map<String, JsonElement>.toRuntimeValues(): Map<String, String> =
    mapValues { (_, value) -> workflowJson.encodeToString(JsonElement.serializer(), value) }

fun Map<String, String>.toWorkflowValues(): Map<String, JsonElement> =
    mapValues { (_, value) -> decodeRuntimeValue(value) }

fun List<WorkflowRunStepProgress>.toRuntimeProgress(): List<RuntimeWorkflowRunStepProgress> =
    map { step -> step.toRuntimeProgress() }

fun List<RuntimeWorkflowRunStepProgress>.toWorkflowProgress(): List<WorkflowRunStepProgress> =
    map { step -> step.toWorkflowProgress() }

private fun WorkflowGraphNode.toRuntimeGraphNode(): RuntimeWorkflowGraphNode =
    RuntimeWorkflowGraphNode(
        id = id,
        type = type,
        trigger = trigger,
        kind = kind,
        action = action,
        params = params.mapValues { (_, value) -> value.workflowStringValue() },
        conditions = conditions,
        cases = cases,
        retry = retry,
        continueOnError = continueOnError,
        position = position
    )

private fun RuntimeWorkflowGraphNode.toWorkflowGraphNode(): WorkflowGraphNode =
    WorkflowGraphNode(
        id = id,
        type = type,
        trigger = trigger,
        kind = kind,
        action = action,
        params = params.mapValues { (_, value) -> JsonPrimitive(value) },
        conditions = conditions,
        cases = cases,
        retry = retry,
        continueOnError = continueOnError,
        position = position
    )

private fun WorkflowRunStepProgress.toRuntimeProgress(): RuntimeWorkflowRunStepProgress =
    RuntimeWorkflowRunStepProgress(
        step = step,
        status = status,
        nodeId = nodeId,
        type = type,
        completedAt = completedAt,
        output = output.toRuntimeValues(),
        errorMessage = errorMessage
    )

private fun RuntimeWorkflowRunStepProgress.toWorkflowProgress(): WorkflowRunStepProgress =
    WorkflowRunStepProgress(
        step = step,
        status = status,
        nodeId = nodeId,
        type = type,
        completedAt = completedAt,
        output = output.toWorkflowValues(),
        errorMessage = errorMessage
    )

private fun decodeRuntimeValue(value: String): JsonElement =
    runCatching {
        workflowJson.decodeFromString(JsonElement.serializer(), value)
    }.getOrElse {
        JsonPrimitive(value)
    }
