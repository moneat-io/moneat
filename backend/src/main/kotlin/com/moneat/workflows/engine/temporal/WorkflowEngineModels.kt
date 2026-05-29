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

import com.moneat.workflows.models.WorkflowRunStepProgress
import com.moneat.workflows.models.WorkflowStepConfig

const val WORKFLOW_TASK_QUEUE = "moneat-workflows-trusted"
internal const val ORGANIZATION_SEARCH_ATTRIBUTE = "organizationId"
internal const val WORKFLOW_SEARCH_ATTRIBUTE = "workflowId"

data class WorkflowStartRequest(
    val runId: Int,
    val workflowId: Int,
    val workflowVersionId: Int,
    val organizationId: Int,
    val triggerName: String,
    val onceFor: String,
    val temporalWorkflowId: String,
    val scope: Map<String, String>
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
    var triggerName: String = "",
    var scope: Map<String, String> = emptyMap()
)

data class WorkflowInterpreterResult(
    var status: String = "",
    var progress: List<WorkflowRunStepProgress> = emptyList(),
    var errorMessage: String? = null
)

data class ExecuteActionInput(
    var organizationId: Int = 0,
    var step: WorkflowStepConfig = WorkflowStepConfig(name = ""),
    var scope: Map<String, String> = emptyMap()
)

data class ExecuteActionResult(
    var status: String = "",
    var completedAt: String = "",
    var errorMessage: String? = null
)

data class LoadRunInput(
    var runId: Int = 0
)

data class WorkflowRunExecutionSnapshot(
    var runId: Int = 0,
    var organizationId: Int = 0,
    var workflowVersionId: Int = 0,
    var steps: List<WorkflowStepConfig> = emptyList(),
    var progress: List<WorkflowRunStepProgress> = emptyList(),
    var scope: Map<String, String> = emptyMap()
)

data class PersistRunProgressInput(
    var runId: Int = 0,
    var status: String = "",
    var progress: List<WorkflowRunStepProgress> = emptyList()
)

data class PersistRunFailureInput(
    var runId: Int = 0,
    var progress: List<WorkflowRunStepProgress> = emptyList(),
    var errorMessage: String = ""
)
