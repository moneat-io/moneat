// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.models

import kotlinx.serialization.Serializable

/**
 * A deliberately small control-flow language for escalation paths.  The
 * server validates every field before a path can be published; callers cannot
 * inject executable expressions into a path.
 */
@Serializable
data class EscalationPath(
    val startNodeId: String? = null,
    val nodes: List<EscalationPathNode>,
    val maxTransitions: Int = DEFAULT_MAX_TRANSITIONS,
) {
    companion object {
        const val DEFAULT_MAX_TRANSITIONS = 64
        const val MAX_NODES = 128
        const val MAX_TARGETS_PER_NODE = 32
        const val MAX_BRANCHES_PER_NODE = 16
    }
}

@Serializable
data class EscalationPathNode(
    val id: String,
    val kind: String = NODE_KIND_LEVEL,
    val delivery: String = DELIVERY_SEQUENTIAL,
    val targets: List<EscalationPathTarget> = emptyList(),
    val waitMinutes: Int = 0,
    val repeatCount: Int = 0,
    val onAcknowledgement: String = ACK_STOP,
    val onTimeout: String = TIMEOUT_NEXT,
    val nextNodeId: String? = null,
    val branches: List<EscalationPathBranch> = emptyList(),
    val stop: Boolean = false,
) {
    companion object {
        const val NODE_KIND_LEVEL = "LEVEL"
        const val NODE_KIND_BRANCH = "BRANCH"
        const val DELIVERY_SEQUENTIAL = "SEQUENTIAL"
        const val DELIVERY_PARALLEL = "PARALLEL"
        const val DELIVERY_PAGE_ALL = "PAGE_ALL"
        const val DELIVERY_ROUND_ROBIN = "ROUND_ROBIN"
        const val ACK_STOP = "STOP"
        const val ACK_NEXT = "NEXT"
        const val ACK_CONTINUE = "CONTINUE"
        const val TIMEOUT_NEXT = "NEXT"
        const val TIMEOUT_REPEAT = "REPEAT"
        const val TIMEOUT_STOP = "STOP"
    }
}

@Serializable
data class EscalationPathTarget(
    val targetType: String,
    val targetResourceId: String,
)

@Serializable
data class EscalationPathBranch(
    val field: String,
    val operator: String,
    val value: String,
    val nextNodeId: String,
)

object EscalationPathTargetType {
    const val USER = "USER"
    const val ON_CALL_SCHEDULE = "ON_CALL_SCHEDULE"
    const val TEAM = "TEAM"
    const val ESCALATION_POLICY = "ESCALATION_POLICY"
    const val SLACK_CHANNEL = "SLACK_CHANNEL"
}

object EscalationPathBranchField {
    const val PRIORITY = "PRIORITY"
    const val SEVERITY = "SEVERITY"
    const val WORKING_HOURS = "WORKING_HOURS"
}

/** Validation errors are stable enough for API clients and tests to consume. */
class EscalationPathValidationException(message: String) : IllegalArgumentException(message)

object EscalationPathValidator {
    private val deliveries = setOf(
        EscalationPathNode.DELIVERY_SEQUENTIAL,
        EscalationPathNode.DELIVERY_PARALLEL,
        EscalationPathNode.DELIVERY_PAGE_ALL,
        EscalationPathNode.DELIVERY_ROUND_ROBIN,
    )
    private val acknowledgementActions = setOf(
        EscalationPathNode.ACK_STOP,
        EscalationPathNode.ACK_NEXT,
        EscalationPathNode.ACK_CONTINUE,
    )
    private val timeoutActions = setOf(
        EscalationPathNode.TIMEOUT_NEXT,
        EscalationPathNode.TIMEOUT_REPEAT,
        EscalationPathNode.TIMEOUT_STOP,
    )
    private val branchFields = setOf(
        EscalationPathBranchField.PRIORITY,
        EscalationPathBranchField.SEVERITY,
        EscalationPathBranchField.WORKING_HOURS,
    )
    private val operators = setOf("EQ", "NEQ", "IN")
    private val priorities = setOf("P0", "P1", "P2", "P3", "P4", "P5")
    private val severities = setOf("SEV-0", "SEV-1", "SEV-2", "SEV-3", "SEV-4")

    fun validate(path: EscalationPath): EscalationPath {
        require(path.nodes.isNotEmpty()) { "Escalation path must contain at least one node" }
        require(path.nodes.size <= EscalationPath.MAX_NODES) {
            "Escalation path cannot contain more than ${EscalationPath.MAX_NODES} nodes"
        }
        require(path.maxTransitions in 1..EscalationPath.DEFAULT_MAX_TRANSITIONS) {
            "maxTransitions must be between 1 and ${EscalationPath.DEFAULT_MAX_TRANSITIONS}"
        }

        val ids = path.nodes.map { node -> node.id.trim() }
        require(ids.none { it.isEmpty() }) { "Escalation path node IDs must not be blank" }
        require(ids.size == ids.toSet().size) { "Escalation path node IDs must be unique" }
        val nodeIds = ids.toSet()
        val startNodeId = path.startNodeId?.trim() ?: ids.first()
        require(startNodeId in nodeIds) { "Escalation path start node does not exist" }

        path.nodes.forEach { node ->
            require(node.id == node.id.trim()) { "Escalation path node IDs must not contain surrounding whitespace" }
            require(
                node.kind == EscalationPathNode.NODE_KIND_LEVEL ||
                    node.kind == EscalationPathNode.NODE_KIND_BRANCH,
            ) {
                "Unsupported escalation path node kind: ${node.kind}"
            }
            require(node.delivery in deliveries) { "Unsupported escalation delivery: ${node.delivery}" }
            require(node.waitMinutes in 0..1440) { "Escalation wait must be between 0 and 1440 minutes" }
            require(node.repeatCount in 0..10) { "Escalation repeat count must be between 0 and 10" }
            require(node.onAcknowledgement in acknowledgementActions) {
                "Unsupported acknowledgement action: ${node.onAcknowledgement}"
            }
            require(node.onTimeout in timeoutActions) { "Unsupported timeout action: ${node.onTimeout}" }
            require(node.targets.size <= EscalationPath.MAX_TARGETS_PER_NODE) {
                "Escalation path node cannot contain more than ${EscalationPath.MAX_TARGETS_PER_NODE} targets"
            }
            require(node.branches.size <= EscalationPath.MAX_BRANCHES_PER_NODE) {
                "Escalation path node cannot contain more than ${EscalationPath.MAX_BRANCHES_PER_NODE} branches"
            }
            if (node.kind == EscalationPathNode.NODE_KIND_LEVEL) {
                require(node.targets.isNotEmpty()) { "Escalation level ${node.id} must have at least one target" }
            } else {
                require(node.targets.isEmpty()) { "Branch node ${node.id} cannot have delivery targets" }
                require(node.branches.isNotEmpty()) { "Branch node ${node.id} must have at least one branch" }
            }
            node.nextNodeId?.let { require(it in nodeIds) { "Node ${node.id} points to a missing next node" } }
            node.branches.forEach { branch ->
                require(branch.field in branchFields) { "Unsupported escalation branch field: ${branch.field}" }
                require(branch.operator in operators) { "Unsupported escalation branch operator: ${branch.operator}" }
                require(branch.value.isNotBlank()) { "Escalation branch values must not be blank" }
                require(branch.nextNodeId in nodeIds) { "Branch ${node.id} points to a missing next node" }
                when (branch.field) {
                    EscalationPathBranchField.PRIORITY -> require(branch.value in priorities) {
                        "Unsupported priority branch value: ${branch.value}"
                    }
                    EscalationPathBranchField.SEVERITY -> require(branch.value in severities) {
                        "Unsupported severity branch value: ${branch.value}"
                    }
                    EscalationPathBranchField.WORKING_HOURS -> require(branch.value in setOf("IN", "OUT")) {
                        "Working-hours branch value must be IN or OUT"
                    }
                }
            }
        }

        val adjacency = path.nodes.associate { node ->
            node.id to listOfNotNull(node.nextNodeId) + node.branches.map { it.nextNodeId }
        }
        detectCycles(startNodeId, adjacency)
        val reachable = reachableNodes(startNodeId, adjacency)
        require(reachable.size == nodeIds.size) { "Escalation path contains unreachable nodes" }
        return path.copy(startNodeId = startNodeId)
    }

    private fun detectCycles(start: String, adjacency: Map<String, List<String>>) {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(nodeId: String) {
            if (nodeId in visiting) throw EscalationPathValidationException("Escalation path contains a cycle")
            if (!visited.add(nodeId)) return
            visiting += nodeId
            adjacency[nodeId].orEmpty().forEach(::visit)
            visiting -= nodeId
        }
        visit(start)
    }

    private fun reachableNodes(start: String, adjacency: Map<String, List<String>>): Set<String> {
        val reachable = mutableSetOf<String>()
        fun visit(nodeId: String) {
            if (!reachable.add(nodeId)) return
            adjacency[nodeId].orEmpty().forEach(::visit)
        }
        visit(start)
        return reachable
    }
}
