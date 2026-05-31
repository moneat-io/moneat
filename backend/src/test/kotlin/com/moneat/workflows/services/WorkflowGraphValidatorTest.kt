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

import com.moneat.workflows.engine.temporal.HTTP_REQUEST_ACTION
import com.moneat.workflows.engine.temporal.WORKFLOWS_EGRESS_ENABLED_ENV
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowGraphEdge
import com.moneat.workflows.models.WorkflowGraphNode
import com.moneat.workflows.models.WorkflowRetryConfig
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowSwitchCaseConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkflowGraphValidatorTest {

    // ──── Fixture ────

    private val validator = WorkflowGraphValidator()

    // ──── Tests ────

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

    // ──── Trigger-level rejections ────

    @Test
    fun `rejects unknown trigger`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("not.a.real.trigger", singleTriggerGraph("not.a.real.trigger"), emptyList())
        }
    }

    @Test
    fun `rejects graphs without exactly one trigger node`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("alert.triggered", WorkflowGraphConfig(nodes = emptyList()), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(nodes = listOf(trigger(), trigger())),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects trigger node whose trigger name differs`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(WorkflowGraphNode(id = "trigger", type = "trigger", trigger = "incident.created"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects duplicate node ids`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), action("dup"), action("dup")),
                    edges = listOf(WorkflowGraphEdge("trigger", "dup"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects unknown once-for references`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = singleTriggerGraph("alert.triggered"),
                onceForTemplate = listOf("not.a.reference")
            )
        }
    }

    // ──── Edge rejections ────

    @Test
    fun `rejects edges with unknown source or target`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), action("a1")),
                    edges = listOf(WorkflowGraphEdge("ghost", "a1"))
                ),
                onceForTemplate = emptyList()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), action("a1")),
                    edges = listOf(WorkflowGraphEdge("trigger", "a1"), WorkflowGraphEdge("a1", "ghost"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects branch edge from a non-branching node`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), action("a1")),
                    edges = listOf(WorkflowGraphEdge("trigger", "a1", branch = "true"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects error edge from a non-action node`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(
                        trigger(),
                        WorkflowGraphNode(id = "c1", type = "condition", kind = "if"),
                        action("a1")
                    ),
                    edges = listOf(
                        WorkflowGraphEdge("trigger", "c1", on = "error"),
                        WorkflowGraphEdge("c1", "a1", branch = "true")
                    )
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    // ──── Node config rejections ────

    @Test
    fun `rejects unsupported condition kind`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), WorkflowGraphNode(id = "c1", type = "condition", kind = "maybe")),
                    edges = listOf(WorkflowGraphEdge("trigger", "c1"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects switch node without cases and validates case conditions`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), WorkflowGraphNode(id = "s1", type = "condition", kind = "switch")),
                    edges = listOf(WorkflowGraphEdge("trigger", "s1"))
                ),
                onceForTemplate = emptyList()
            )
        }
        validator.validate(
            triggerName = "alert.triggered",
            graph = WorkflowGraphConfig(
                nodes = listOf(
                    trigger(),
                    WorkflowGraphNode(
                        id = "s1",
                        type = "condition",
                        kind = "switch",
                        cases = listOf(
                            WorkflowSwitchCaseConfig(
                                name = "high",
                                conditions = listOf(WorkflowConditionConfig("alert.severity", "at_least", "HIGH"))
                            )
                        )
                    ),
                    action("a1")
                ),
                edges = listOf(
                    WorkflowGraphEdge("trigger", "s1"),
                    WorkflowGraphEdge("s1", "a1", branch = "high")
                )
            ),
            onceForTemplate = emptyList()
        )
    }

    @Test
    fun `rejects action node missing action or referencing unknown step`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), WorkflowGraphNode(id = "a1", type = "action", action = null)),
                    edges = listOf(WorkflowGraphEdge("trigger", "a1"))
                ),
                onceForTemplate = emptyList()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), WorkflowGraphNode(id = "a1", type = "action", action = "no.such.step")),
                    edges = listOf(WorkflowGraphEdge("trigger", "a1"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects action node missing a required parameter`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(
                        trigger(),
                        WorkflowGraphNode(id = "a1", type = "action", action = "notification.slack")
                    ),
                    edges = listOf(WorkflowGraphEdge("trigger", "a1"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects egress actions when the egress feature is disabled`() {
        System.clearProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = egressActionGraph(),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `accepts egress actions when the egress feature is enabled`() {
        System.setProperty(WORKFLOWS_EGRESS_ENABLED_ENV, "true")
        try {
            validator.validate(
                triggerName = "alert.triggered",
                graph = egressActionGraph(),
                onceForTemplate = emptyList()
            )
        } finally {
            System.clearProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
        }
    }

    @Test
    fun `rejects retry attempts outside the allowed range`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(
                        trigger(),
                        action("a1").copy(retry = WorkflowRetryConfig(maxAttempts = 0))
                    ),
                    edges = listOf(WorkflowGraphEdge("trigger", "a1"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    // ──── Control node rejections ────

    @Test
    fun `rejects sleep node without duration`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), WorkflowGraphNode(id = "z1", type = "control", kind = "sleep")),
                    edges = listOf(WorkflowGraphEdge("trigger", "z1"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `rejects bounded control above the cap and unknown control kind`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(
                        trigger(),
                        WorkflowGraphNode(
                            id = "loop",
                            type = "control",
                            kind = "for_each",
                            params = mapOf("max_items" to JsonPrimitive("99999"))
                        )
                    ),
                    edges = listOf(WorkflowGraphEdge("trigger", "loop", branch = "body"))
                ),
                onceForTemplate = emptyList()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), WorkflowGraphNode(id = "c", type = "control", kind = "teleport")),
                    edges = listOf(WorkflowGraphEdge("trigger", "c"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    @Test
    fun `validates wait_until control conditions`() {
        validator.validate(
            triggerName = "alert.triggered",
            graph = WorkflowGraphConfig(
                nodes = listOf(
                    trigger(),
                    WorkflowGraphNode(
                        id = "wait",
                        type = "control",
                        kind = "wait_until",
                        conditions = listOf(WorkflowConditionConfig("alert.status", "eq", "RESOLVED"))
                    ),
                    action("a1")
                ),
                edges = listOf(
                    WorkflowGraphEdge("trigger", "wait"),
                    WorkflowGraphEdge("wait", "a1", branch = "true")
                )
            ),
            onceForTemplate = emptyList()
        )
    }

    @Test
    fun `validates approval control nodes`() {
        validator.validate(
            triggerName = "alert.triggered",
            graph = WorkflowGraphConfig(
                nodes = listOf(
                    trigger(),
                    WorkflowGraphNode(
                        id = "approval",
                        type = "control",
                        kind = "approval",
                        params = mapOf(
                            "message" to JsonPrimitive("Approve remediation"),
                            "timeout" to JsonPrimitive("PT1H")
                        )
                    ),
                    action("a1")
                ),
                edges = listOf(
                    WorkflowGraphEdge("trigger", "approval"),
                    WorkflowGraphEdge("approval", "a1", branch = "approved")
                )
            ),
            onceForTemplate = emptyList()
        )
    }

    @Test
    fun `rejects unsupported node type`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = WorkflowGraphConfig(
                    nodes = listOf(trigger(), WorkflowGraphNode(id = "x", type = "wormhole")),
                    edges = listOf(WorkflowGraphEdge("trigger", "x"))
                ),
                onceForTemplate = emptyList()
            )
        }
    }

    // ──── Condition reference rejections ────

    @Test
    fun `rejects unknown condition reference and accepts steps-prefixed references`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = ifConditionGraph(WorkflowConditionConfig("alert.unknown_field", "eq", "x")),
                onceForTemplate = emptyList()
            )
        }
        validator.validate(
            triggerName = "alert.triggered",
            graph = ifConditionGraph(WorkflowConditionConfig("steps.previous.value", "is_set")),
            onceForTemplate = emptyList()
        )
    }

    @Test
    fun `rejects unsupported operation and missing value for scope condition`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = ifConditionGraph(WorkflowConditionConfig("alert.status", "at_least", "HIGH")),
                onceForTemplate = emptyList()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(
                triggerName = "alert.triggered",
                graph = ifConditionGraph(WorkflowConditionConfig("alert.status", "eq", value = null)),
                onceForTemplate = emptyList()
            )
        }
    }

    // ──── validateLegacy ────

    @Test
    fun `validateLegacy builds and validates a graph`() {
        validator.validateLegacy(
            triggerName = "alert.triggered",
            conditions = listOf(WorkflowConditionConfig("alert.severity", "at_least", "HIGH")),
            steps = listOf(
                WorkflowStepConfig("notification.slack", mapOf("message" to "{{alert.title}}"))
            ),
            onceForTemplate = listOf("alert.deduplication_key")
        )
        assertFailsWith<IllegalArgumentException> {
            validator.validateLegacy(
                triggerName = "alert.triggered",
                conditions = emptyList(),
                steps = listOf(WorkflowStepConfig("no.such.step")),
                onceForTemplate = emptyList()
            )
        }
    }

    // ──── Helpers ────

    private fun singleTriggerGraph(triggerName: String): WorkflowGraphConfig =
        WorkflowGraphConfig(
            nodes = listOf(WorkflowGraphNode(id = "trigger", type = "trigger", trigger = triggerName))
        )

    private fun ifConditionGraph(condition: WorkflowConditionConfig): WorkflowGraphConfig =
        WorkflowGraphConfig(
            nodes = listOf(
                trigger(),
                WorkflowGraphNode(id = "c1", type = "condition", kind = "if", conditions = listOf(condition)),
                action("a1")
            ),
            edges = listOf(
                WorkflowGraphEdge("trigger", "c1"),
                WorkflowGraphEdge("c1", "a1", branch = "true")
            )
        )

    private fun egressActionGraph(): WorkflowGraphConfig =
        WorkflowGraphConfig(
            nodes = listOf(
                trigger(),
                WorkflowGraphNode(
                    id = "egress-1",
                    type = "action",
                    action = HTTP_REQUEST_ACTION,
                    params = mapOf("url" to JsonPrimitive("https://example.com/hook"))
                )
            ),
            edges = listOf(WorkflowGraphEdge("trigger", "egress-1"))
        )

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
