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

import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphEdge
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.workflowStringValue
import kotlinx.serialization.json.JsonPrimitive

object LinearGraphAdapter {
    fun fromSteps(steps: List<WorkflowStepConfig>): List<LinearWorkflowNode> =
        steps.mapIndexed { index, step ->
            LinearWorkflowNode(id = "step-${index + 1}", step = step)
        }

    fun graphFromLegacy(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>,
        steps: List<WorkflowStepConfig>
    ): WorkflowGraphConfig {
        val triggerNode = WorkflowGraphNode(
            id = TRIGGER_NODE_ID,
            type = NODE_TYPE_TRIGGER,
            trigger = triggerName
        )
        val conditionNode =
            if (conditions.isEmpty()) {
                null
            } else {
                WorkflowGraphNode(
                    id = LEGACY_CONDITION_NODE_ID,
                    type = NODE_TYPE_CONDITION,
                    kind = CONDITION_KIND_IF,
                    conditions = conditions
                )
            }
        val actionNodes = steps.mapIndexed { index, step ->
            WorkflowGraphNode(
                id = "step-${index + 1}",
                type = NODE_TYPE_ACTION,
                action = step.name,
                params = step.params.mapValues { (_, value) -> JsonPrimitive(value) },
                continueOnError = true
            )
        }
        return WorkflowGraphConfig(
            nodes = listOfNotNull(triggerNode, conditionNode) + actionNodes,
            edges = legacyEdges(conditionNode != null, actionNodes)
        )
    }

    fun stepsFromGraph(graph: WorkflowGraphConfig): List<WorkflowStepConfig> =
        graph.nodes
            .filter { node -> node.type == NODE_TYPE_ACTION && !node.action.isNullOrBlank() }
            .map { node ->
                WorkflowStepConfig(
                    name = checkNotNull(node.action),
                    params = node.params.mapValues { (_, value) -> value.workflowParamValue() }
                )
            }

    fun conditionsFromGraph(graph: WorkflowGraphConfig): List<WorkflowConditionConfig> =
        graph.nodes
            .firstOrNull { node -> node.type == NODE_TYPE_CONDITION && node.kind == CONDITION_KIND_IF }
            ?.conditions
            .orEmpty()

    private fun legacyEdges(
        hasCondition: Boolean,
        actionNodes: List<WorkflowGraphNode>
    ): List<WorkflowGraphEdge> {
        val edges = mutableListOf<WorkflowGraphEdge>()
        val firstAction = actionNodes.firstOrNull()
        when {
            hasCondition && firstAction != null -> {
                edges += WorkflowGraphEdge(TRIGGER_NODE_ID, LEGACY_CONDITION_NODE_ID)
                edges += WorkflowGraphEdge(LEGACY_CONDITION_NODE_ID, firstAction.id, branch = "true")
            }
            hasCondition -> edges += WorkflowGraphEdge(TRIGGER_NODE_ID, LEGACY_CONDITION_NODE_ID)
            firstAction != null -> edges += WorkflowGraphEdge(TRIGGER_NODE_ID, firstAction.id)
        }
        actionNodes.zipWithNext().forEach { (from, to) ->
            edges += WorkflowGraphEdge(from.id, to.id)
        }
        return edges
    }

    private fun kotlinx.serialization.json.JsonElement.workflowParamValue(): String =
        workflowStringValue()

    const val TRIGGER_NODE_ID = "trigger"
    const val LEGACY_CONDITION_NODE_ID = "conditions"
    const val NODE_TYPE_TRIGGER = "trigger"
    const val NODE_TYPE_CONDITION = "condition"
    const val NODE_TYPE_ACTION = "action"
    const val NODE_TYPE_CONTROL = "control"
    const val CONDITION_KIND_IF = "if"
    const val CONDITION_KIND_SWITCH = "switch"
    const val CONTROL_KIND_SLEEP = "sleep"
    const val CONTROL_KIND_WAIT_UNTIL = "wait_until"
    const val CONTROL_KIND_FOR_EACH = "for_each"
    const val CONTROL_KIND_WHILE = "while"
}

data class LinearWorkflowNode(
    val id: String,
    val step: WorkflowStepConfig
)
