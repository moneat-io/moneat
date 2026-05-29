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

import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.engine.temporal.LinearGraphAdapter
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.workflowStringValue

private const val DEFAULT_MAX_LOOP_ITEMS = 100
private const val MAX_LOOP_ITEMS = 2_000

class WorkflowGraphValidator {
    fun validate(
        triggerName: String,
        graph: WorkflowGraphConfig,
        onceForTemplate: List<String>
    ) {
        val trigger = WorkflowCatalog.trigger(triggerName)
            ?: throw IllegalArgumentException("Unknown workflow trigger $triggerName")
        val triggerNodes = graph.nodes.filter { node -> node.type == LinearGraphAdapter.NODE_TYPE_TRIGGER }
        require(triggerNodes.size == 1) { "Workflow graph must contain exactly one trigger node" }
        require(triggerNodes.single().trigger == triggerName) {
            "Workflow trigger node must match $triggerName"
        }
        require(graph.nodes.map { node -> node.id }.toSet().size == graph.nodes.size) {
            "Workflow graph node IDs must be unique"
        }
        validateEdges(graph)
        validateReachability(graph, triggerNodes.single())
        validateCycles(graph)
        validateNodeConfigs(triggerName, graph)
        val scopeReferences = trigger.scope.map { it.name }.toSet()
        onceForTemplate.forEach { reference ->
            require(reference in scopeReferences) { "Unknown once-for reference $reference" }
        }
    }

    fun validateLegacy(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>,
        steps: List<WorkflowStepConfig>,
        onceForTemplate: List<String>
    ) {
        validate(
            triggerName = triggerName,
            graph = LinearGraphAdapter.graphFromLegacy(triggerName, conditions, steps),
            onceForTemplate = onceForTemplate
        )
    }

    private fun validateEdges(graph: WorkflowGraphConfig) {
        val nodesById = graph.nodes.associateBy { node -> node.id }
        graph.edges.forEach { edge ->
            val from = nodesById[edge.from] ?: throw IllegalArgumentException("Unknown graph edge source ${edge.from}")
            require(edge.to in nodesById) { "Unknown graph edge target ${edge.to}" }
            if (!edge.branch.isNullOrBlank()) {
                require(
                    from.type == LinearGraphAdapter.NODE_TYPE_CONDITION ||
                        from.type == LinearGraphAdapter.NODE_TYPE_CONTROL
                ) {
                    "Branch edges must start from condition or control nodes"
                }
            }
            if (edge.on == "error") {
                require(from.type == LinearGraphAdapter.NODE_TYPE_ACTION) {
                    "Error edges must start from action nodes"
                }
            }
        }
    }

    private fun validateReachability(
        graph: WorkflowGraphConfig,
        triggerNode: WorkflowGraphNode
    ) {
        val edgesBySource = graph.edges.groupBy { edge -> edge.from }
        val reachable = mutableSetOf<String>()
        fun visit(nodeId: String) {
            if (!reachable.add(nodeId)) return
            edgesBySource[nodeId].orEmpty().forEach { edge -> visit(edge.to) }
        }
        visit(triggerNode.id)
        val orphan = graph.nodes.firstOrNull { node -> node.id !in reachable }
        require(orphan == null) { "Workflow graph contains unreachable node ${orphan?.id}" }
    }

    private fun validateCycles(graph: WorkflowGraphConfig) {
        val nodesById = graph.nodes.associateBy { node -> node.id }
        val edgesBySource = graph.edges.groupBy { edge -> edge.from }
        val visiting = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(nodeId: String) {
            if (nodeId in visited) return
            val cycleStart = visiting.indexOf(nodeId)
            if (cycleStart >= 0) {
                val cycle = visiting.drop(cycleStart)
                require(cycle.any { id -> nodesById[id]?.isLoopControl() == true }) {
                    "Workflow graph contains an illegal cycle"
                }
                return
            }
            visiting += nodeId
            edgesBySource[nodeId].orEmpty().forEach { edge -> visit(edge.to) }
            visiting.removeLast()
            visited += nodeId
        }
        graph.nodes
            .filter { node -> node.type == LinearGraphAdapter.NODE_TYPE_TRIGGER }
            .forEach { node -> visit(node.id) }
    }

    private fun validateNodeConfigs(
        triggerName: String,
        graph: WorkflowGraphConfig
    ) {
        graph.nodes.forEach { node ->
            when (node.type) {
                LinearGraphAdapter.NODE_TYPE_TRIGGER -> Unit
                LinearGraphAdapter.NODE_TYPE_CONDITION -> validateConditionNode(triggerName, node)
                LinearGraphAdapter.NODE_TYPE_ACTION -> validateActionNode(node)
                LinearGraphAdapter.NODE_TYPE_CONTROL -> validateControlNode(triggerName, node)
                else -> throw IllegalArgumentException("Unsupported workflow graph node type ${node.type}")
            }
        }
    }

    private fun validateConditionNode(
        triggerName: String,
        node: WorkflowGraphNode
    ) {
        require(
            node.kind == LinearGraphAdapter.CONDITION_KIND_IF ||
                node.kind == LinearGraphAdapter.CONDITION_KIND_SWITCH
        ) {
            "Unsupported condition node kind ${node.kind}"
        }
        if (node.kind == LinearGraphAdapter.CONDITION_KIND_SWITCH) {
            require(node.cases.isNotEmpty()) { "Switch node ${node.id} must define at least one case" }
            node.cases.forEach { item -> validateConditions(triggerName, item.conditions) }
        } else {
            validateConditions(triggerName, node.conditions)
        }
    }

    private fun validateActionNode(node: WorkflowGraphNode) {
        val action = node.action ?: throw IllegalArgumentException("Action node ${node.id} is missing an action")
        val definition = WorkflowCatalog.step(action)
            ?: throw IllegalArgumentException("Unknown workflow step $action")
        definition.params.filter { param -> param.required }.forEach { param ->
            require(!node.params[param.name]?.workflowStringValue().isNullOrBlank()) {
                "Missing required parameter ${param.name} for $action"
            }
        }
        node.retry?.let { retry ->
            require(retry.maxAttempts in 1..MAX_LOOP_ITEMS) {
                "Retry attempts for ${node.id} must be between 1 and $MAX_LOOP_ITEMS"
            }
        }
    }

    private fun validateControlNode(
        triggerName: String,
        node: WorkflowGraphNode
    ) {
        when (node.kind) {
            LinearGraphAdapter.CONTROL_KIND_SLEEP -> {
                require(!node.params["duration"]?.workflowStringValue().isNullOrBlank()) {
                    "Sleep node ${node.id} must define duration"
                }
            }
            LinearGraphAdapter.CONTROL_KIND_WAIT_UNTIL -> validateConditions(triggerName, node.conditions)
            LinearGraphAdapter.CONTROL_KIND_FOR_EACH -> validateBoundedControl(node, "max_items")
            LinearGraphAdapter.CONTROL_KIND_WHILE -> {
                validateBoundedControl(node, "max_iterations")
                validateConditions(triggerName, node.conditions)
            }
            else -> throw IllegalArgumentException("Unsupported control node kind ${node.kind}")
        }
    }

    private fun validateBoundedControl(
        node: WorkflowGraphNode,
        paramName: String
    ) {
        val value =
            node.params[paramName]
                ?.workflowStringValue()
                ?.toIntOrNull()
                ?: DEFAULT_MAX_LOOP_ITEMS
        require(value in 1..MAX_LOOP_ITEMS) {
            "Control node ${node.id} must set $paramName between 1 and $MAX_LOOP_ITEMS"
        }
    }

    private fun validateConditions(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>
    ) {
        val trigger = checkNotNull(WorkflowCatalog.trigger(triggerName))
        val scopeReferences = trigger.scope.map { it.name }.toSet()
        conditions.forEach { condition ->
            require(condition.reference in scopeReferences || condition.reference.startsWith("steps.")) {
                "Unknown workflow condition reference ${condition.reference}"
            }
            if (condition.reference in scopeReferences) {
                validateScopeCondition(triggerName, condition)
            }
        }
    }

    private fun validateScopeCondition(
        triggerName: String,
        condition: WorkflowConditionConfig
    ) {
        val resourceType = checkNotNull(WorkflowCatalog.scopeType(triggerName, condition.reference))
        val resource = checkNotNull(WorkflowCatalog.resources.firstOrNull { item -> item.type == resourceType })
        require(resource.operations.any { operation -> operation.name == condition.operation }) {
            "Unsupported operation ${condition.operation} for ${condition.reference}"
        }
        if (condition.operation.requiresConditionValue()) {
            require(!condition.value.isNullOrBlank()) {
                "Missing value for ${condition.reference} ${condition.operation}"
            }
        }
    }

    private fun WorkflowGraphNode.isLoopControl(): Boolean =
        type == LinearGraphAdapter.NODE_TYPE_CONTROL &&
            kind in setOf(LinearGraphAdapter.CONTROL_KIND_FOR_EACH, LinearGraphAdapter.CONTROL_KIND_WHILE)

    private fun String.requiresConditionValue(): Boolean =
        this !in setOf("is_set", "is_not_set")
}
