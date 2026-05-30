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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearGraphAdapterTest {

    // ──── fromSteps ────

    @Test
    fun `fromSteps assigns sequential ids`() {
        val nodes =
            LinearGraphAdapter.fromSteps(
                listOf(WorkflowStepConfig("notification.slack"), WorkflowStepConfig("notification.email"))
            )
        assertEquals(listOf("step-1", "step-2"), nodes.map { it.id })
        assertEquals("notification.slack", nodes.first().step.name)
    }

    // ──── graphFromLegacy ────

    @Test
    fun `graphFromLegacy without conditions fans actions out from the trigger`() {
        val graph =
            LinearGraphAdapter.graphFromLegacy(
                triggerName = "alert.triggered",
                conditions = emptyList(),
                steps = listOf(
                    WorkflowStepConfig("notification.slack", mapOf("message" to "hi")),
                    WorkflowStepConfig("notification.email")
                )
            )

        assertEquals(3, graph.nodes.size)
        val trigger = graph.nodes.first()
        assertEquals(LinearGraphAdapter.NODE_TYPE_TRIGGER, trigger.type)
        assertEquals("alert.triggered", trigger.trigger)
        // Each action carries params as JSON primitives and is independent (continueOnError = true).
        val firstAction = graph.nodes[1]
        assertEquals(LinearGraphAdapter.NODE_TYPE_ACTION, firstAction.type)
        assertTrue(graph.nodes.drop(1).all { it.continueOnError })
        assertEquals("hi", firstAction.params["message"]?.workflowStringValue())
        // Edges fan out: trigger -> every action, with nothing chaining the actions together.
        assertEquals(
            listOf("trigger" to "step-1", "trigger" to "step-2"),
            graph.edges.map { it.from to it.to }
        )
        assertTrue(graph.edges.all { it.branch == null })
    }

    @Test
    fun `graphFromLegacy with conditions fans actions out from the condition true branch`() {
        val graph =
            LinearGraphAdapter.graphFromLegacy(
                triggerName = "alert.triggered",
                conditions = listOf(WorkflowConditionConfig("alert.severity", "at_least", "HIGH")),
                steps = listOf(
                    WorkflowStepConfig("notification.slack"),
                    WorkflowStepConfig("notification.email")
                )
            )

        val conditionNode = graph.nodes.single { it.type == LinearGraphAdapter.NODE_TYPE_CONDITION }
        assertEquals(LinearGraphAdapter.LEGACY_CONDITION_NODE_ID, conditionNode.id)
        assertEquals(LinearGraphAdapter.CONDITION_KIND_IF, conditionNode.kind)
        // trigger -> conditions, then conditions -> every action on the "true" branch.
        assertEquals(WorkflowGraphEdge("trigger", "conditions"), graph.edges.first())
        val branchEdges = graph.edges.filter { it.branch == "true" }
        assertEquals(
            listOf("conditions" to "step-1", "conditions" to "step-2"),
            branchEdges.map { it.from to it.to }
        )
    }

    @Test
    fun `graphFromLegacy with conditions and no steps only links trigger to condition`() {
        val graph =
            LinearGraphAdapter.graphFromLegacy(
                triggerName = "alert.triggered",
                conditions = listOf(WorkflowConditionConfig("alert.status", "eq", "FIRING")),
                steps = emptyList()
            )
        assertEquals(listOf(WorkflowGraphEdge("trigger", "conditions")), graph.edges)
    }

    @Test
    fun `graphFromLegacy with no conditions and no steps has only the trigger node`() {
        val graph =
            LinearGraphAdapter.graphFromLegacy(
                triggerName = "manual",
                conditions = emptyList(),
                steps = emptyList()
            )
        assertEquals(1, graph.nodes.size)
        assertTrue(graph.edges.isEmpty())
    }

    // ──── stepsFromGraph ────

    @Test
    fun `stepsFromGraph extracts action nodes and skips blanks`() {
        val graph =
            WorkflowGraphConfig(
                nodes = listOf(
                    WorkflowGraphNode("trigger", "trigger", trigger = "alert.triggered"),
                    WorkflowGraphNode(
                        id = "step-1",
                        type = "action",
                        action = "notification.slack",
                        params = mapOf("message" to JsonPrimitive("hello"))
                    ),
                    WorkflowGraphNode(id = "blank", type = "action", action = "  "),
                    WorkflowGraphNode(id = "no-action", type = "action", action = null)
                )
            )
        val steps = LinearGraphAdapter.stepsFromGraph(graph)
        assertEquals(1, steps.size)
        assertEquals("notification.slack", steps.single().name)
        assertEquals("hello", steps.single().params["message"])
    }

    // ──── conditionsFromGraph ────

    @Test
    fun `conditionsFromGraph returns conditions of the first if node`() {
        val expected = WorkflowConditionConfig("alert.severity", "at_least", "HIGH")
        val graph =
            WorkflowGraphConfig(
                nodes = listOf(
                    WorkflowGraphNode(
                        id = "conditions",
                        type = "condition",
                        kind = "if",
                        conditions = listOf(expected)
                    )
                )
            )
        assertEquals(listOf(expected), LinearGraphAdapter.conditionsFromGraph(graph))
    }

    @Test
    fun `conditionsFromGraph returns empty when no if node exists`() {
        val graph =
            WorkflowGraphConfig(
                nodes = listOf(WorkflowGraphNode(id = "trigger", type = "trigger", trigger = "manual"))
            )
        assertTrue(LinearGraphAdapter.conditionsFromGraph(graph).isEmpty())
        assertNull(graph.nodes.firstOrNull { it.kind == "switch" })
    }
}
