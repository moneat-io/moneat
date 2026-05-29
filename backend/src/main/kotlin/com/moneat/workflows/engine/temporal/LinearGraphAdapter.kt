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

import com.moneat.workflows.models.WorkflowStepConfig

object LinearGraphAdapter {
    fun fromSteps(steps: List<WorkflowStepConfig>): List<LinearWorkflowNode> =
        steps.mapIndexed { index, step ->
            LinearWorkflowNode(id = "step-${index + 1}", step = step)
        }
}

data class LinearWorkflowNode(
    val id: String,
    val step: WorkflowStepConfig
)
