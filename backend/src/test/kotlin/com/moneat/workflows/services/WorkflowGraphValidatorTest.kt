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

import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphEdge
import com.moneat.workflows.models.WorkflowGraphNode
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkflowGraphValidatorTest {
    private val validator = WorkflowGraphValidator()

    @Test
    fun `validates branching graph`() {
        validator.validate(
            triggerName = "alert.triggered",
            graph = WorkflowGraphConfig(
                nodes = listOf(
                    trigger(),
                    WorkflowGraphNode(
                        id = "condition-1",
                        type = "condition",
                        kind = "if",
                        conditions = listOf(WorkflowConditionConfig("alert.severity", "at_least", "HIGH"))
                    ),
                    action("action-1")
                ),
                edges = listOf(
                    WorkflowGraphEdge("trigger", "condition-1"),
                    WorkflowGraphEdge("condition-1", "action-1", branch = "true")
                )
            ),
            onceForTemplate = listOf("alert.deduplication_key")
        )
    }

    @Test
    fun `rejects orphan nodes and illegal cycles`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(nodes = listOf(trigger(), action("orphan"))),
                onceForTemplate = emptyList()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), action("a1"), action("a2")),
                    edges = listOf(
                        WorkflowGraphEdge("trigger", "a1"),
                        WorkflowGraphEdge("a1", "a2"),
                        WorkflowGraphEdge("a2", "a1")
                    )
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `validates bounded loop controls`() {
        validator.validate(
            triggerName = "alert.triggered",
            graph = WorkflowGraphConfig(
                nodes = listOf(
                    trigger(),
                    WorkflowGraphNode(
                        id = "while-1",
                        type = "control",
                        kind = "while",
                        params = mapOf("max_iterations" to JsonPrimitive("10")),
                        conditions = listOf(WorkflowConditionConfig("alert.status", "eq", "FIRING"))
                    ),
                    action("action-1")
                ),
                edges = listOf(
                    WorkflowGraphEdge("trigger", "while-1"),
                    WorkflowGraphEdge("while-1", "action-1", branch = "body")
                )
            ),
            onceForTemplate = emptyList()
        )
    }

    private fun trigger(): WorkflowGraphNode =
        WorkflowGraphNode(id = "trigger", type = "trigger", trigger = "alert.triggered")

    private fun action(id: String): WorkflowGraphNode =
        WorkflowGraphNode(
            id = id,
            type = "action",
            action = "notification.slack",
            params = mapOf("message" to JsonPrimitive("{{alert.title}}"))
        )
}
