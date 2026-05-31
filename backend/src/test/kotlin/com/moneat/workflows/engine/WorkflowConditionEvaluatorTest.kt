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

package com.moneat.workflows.engine

import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.typedWorkflowScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowConditionEvaluatorTest {

    // ──── evaluate: presence operators ────

    @Test
    fun `is_set is true only for non-blank actual`() {
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "value", "is_set", null))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "", "is_set", null))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "   ", "is_set", null))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, null, "is_set", null))
    }

    @Test
    fun `is_not_set is true only for blank or null actual`() {
        assertTrue(WorkflowConditionEvaluator.evaluate(null, null, "is_not_set", null))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "", "is_not_set", null))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "value", "is_not_set", null))
    }

    // ──── evaluate: equality operators ────

    @Test
    fun `eq compares case-insensitively`() {
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "FIRING", "eq", "firing"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "FIRING", "eq", "resolved"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, null, "eq", "firing"))
    }

    @Test
    fun `neq is the negation of eq`() {
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "FIRING", "neq", "firing"))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "FIRING", "neq", "resolved"))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, null, "neq", "firing"))
    }

    @Test
    fun `eq treats null expected as empty string`() {
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "", "eq", null))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "x", "eq", null))
    }

    // ──── evaluate: contains operators ────

    @Test
    fun `contains and not_contains cover hit miss and null actual`() {
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "checkout-service", "contains", "CHECKOUT"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "checkout-service", "contains", "billing"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, null, "contains", "x"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "checkout", "not_contains", "checkout"))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "checkout", "not_contains", "billing"))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, null, "not_contains", "x"))
    }

    // ──── evaluate: numeric comparisons ────

    @Test
    fun `numeric operators compare parsed doubles`() {
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "12", "gt", "5"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "5", "gt", "5"))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "5", "gte", "5"))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "1", "lt", "5"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "5", "lt", "5"))
        assertTrue(WorkflowConditionEvaluator.evaluate(null, "5", "lte", "5"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "9", "lte", "5"))
    }

    @Test
    fun `numeric operators return false for non-numeric inputs`() {
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "abc", "gt", "5"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "5", "gt", "abc"))
        assertFalse(WorkflowConditionEvaluator.evaluate(null, null, "gte", "5"))
    }

    // ──── evaluate: severity (at_least) ────

    @Test
    fun `at_least requires a severity resource type and ranks correctly`() {
        assertTrue(WorkflowConditionEvaluator.evaluate("AlertSeverity", "CRITICAL", "at_least", "HIGH"))
        assertTrue(WorkflowConditionEvaluator.evaluate("AlertSeverity", "HIGH", "at_least", "HIGH"))
        assertFalse(WorkflowConditionEvaluator.evaluate("AlertSeverity", "LOW", "at_least", "HIGH"))
        assertTrue(WorkflowConditionEvaluator.evaluate("SecuritySeverity", "high", "at_least", "medium"))
    }

    @Test
    fun `at_least is false for non-severity resource or unknown expected`() {
        assertFalse(WorkflowConditionEvaluator.evaluate("String", "CRITICAL", "at_least", "HIGH"))
        assertFalse(WorkflowConditionEvaluator.evaluate("AlertSeverity", "CRITICAL", "at_least", "BOGUS"))
        assertFalse(WorkflowConditionEvaluator.evaluate("AlertSeverity", "INFO", "at_least", "LOW"))
    }

    @Test
    fun `unknown operation evaluates to false`() {
        assertFalse(WorkflowConditionEvaluator.evaluate(null, "x", "matches_regex", "x"))
    }

    // ──── matchesAll / matchesAny ────

    @Test
    fun `matchesAll requires every condition and reads scope by reference`() {
        val scope =
            mapOf(
                "alert.severity" to "HIGH",
                "alert.status" to "FIRING"
            ).typedWorkflowScope()
        assertTrue(
            WorkflowConditionEvaluator.matchesAll(
                triggerName = "alert.triggered",
                conditions = listOf(
                    WorkflowConditionConfig("alert.severity", "at_least", "MEDIUM"),
                    WorkflowConditionConfig("alert.status", "eq", "FIRING")
                ),
                scope = scope
            )
        )
        assertFalse(
            WorkflowConditionEvaluator.matchesAll(
                triggerName = "alert.triggered",
                conditions = listOf(
                    WorkflowConditionConfig("alert.severity", "at_least", "MEDIUM"),
                    WorkflowConditionConfig("alert.status", "eq", "RESOLVED")
                ),
                scope = scope
            )
        )
    }

    @Test
    fun `matchesAll is vacuously true for no conditions`() {
        assertTrue(WorkflowConditionEvaluator.matchesAll("alert.triggered", emptyList(), emptyMap()))
    }

    @Test
    fun `matchesAny is true for empty conditions and for any match`() {
        val scope = mapOf("alert.status" to "FIRING").typedWorkflowScope()
        assertTrue(WorkflowConditionEvaluator.matchesAny("alert.triggered", emptyList(), emptyMap()))
        assertTrue(
            WorkflowConditionEvaluator.matchesAny(
                triggerName = "alert.triggered",
                conditions = listOf(
                    WorkflowConditionConfig("alert.status", "eq", "RESOLVED"),
                    WorkflowConditionConfig("alert.status", "eq", "FIRING")
                ),
                scope = scope
            )
        )
        assertFalse(
            WorkflowConditionEvaluator.matchesAny(
                triggerName = "alert.triggered",
                conditions = listOf(WorkflowConditionConfig("alert.status", "eq", "RESOLVED")),
                scope = scope
            )
        )
    }
}
