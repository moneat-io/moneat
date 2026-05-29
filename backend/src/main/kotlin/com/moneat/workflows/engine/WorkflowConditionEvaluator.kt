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
import com.moneat.workflows.models.workflowStringValue
import com.moneat.workflows.models.workflowValue
import kotlinx.serialization.json.JsonElement

private const val SEVERITY_CRITICAL_RANK = 4
private const val SEVERITY_HIGH_RANK = 3
private const val SEVERITY_MEDIUM_RANK = 2
private const val SEVERITY_LOW_RANK = 1
private const val SEVERITY_UNKNOWN_RANK = 0

object WorkflowConditionEvaluator {
    fun matchesAll(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>,
        scope: Map<String, JsonElement>
    ): Boolean =
        conditions.all { condition ->
            val actual = scope.workflowValue(condition.reference)?.workflowStringValue()
            val resourceType = WorkflowCatalog.scopeType(triggerName, condition.reference)
            evaluate(resourceType, actual, condition.operation, condition.value)
        }

    fun matchesAny(
        triggerName: String,
        conditions: List<WorkflowConditionConfig>,
        scope: Map<String, JsonElement>
    ): Boolean =
        conditions.isEmpty() || conditions.any { condition ->
            val actual = scope.workflowValue(condition.reference)?.workflowStringValue()
            val resourceType = WorkflowCatalog.scopeType(triggerName, condition.reference)
            evaluate(resourceType, actual, condition.operation, condition.value)
        }

    fun evaluate(
        resourceType: String?,
        actual: String?,
        operation: String,
        expected: String?
    ): Boolean =
        when (operation) {
            "is_set" -> !actual.isNullOrBlank()
            "is_not_set" -> actual.isNullOrBlank()
            "eq" -> equalsIgnoringCase(actual, expected)
            "neq" -> !equalsIgnoringCase(actual, expected)
            "contains" -> actual?.contains(expected.orEmpty(), ignoreCase = true) == true
            "not_contains" -> actual?.contains(expected.orEmpty(), ignoreCase = true) != true
            "gt", "gte", "lt", "lte" -> compareNumbers(actual, expected, operation)
            "at_least" -> {
                val expectedRank = severityRank(expected)
                resourceType == "AlertSeverity" && expectedRank > 0 && severityRank(actual) >= expectedRank
            }
            else -> false
        }

    private fun compareNumbers(
        actual: String?,
        expected: String?,
        operation: String
    ): Boolean {
        val actualNumber = actual?.toDoubleOrNull() ?: return false
        val expectedNumber = expected?.toDoubleOrNull() ?: return false
        return when (operation) {
            "gt" -> actualNumber > expectedNumber
            "gte" -> actualNumber >= expectedNumber
            "lt" -> actualNumber < expectedNumber
            "lte" -> actualNumber <= expectedNumber
            else -> false
        }
    }

    private fun equalsIgnoringCase(
        actual: String?,
        expected: String?
    ): Boolean =
        actual != null && actual.compareTo(expected.orEmpty(), ignoreCase = true) == 0

    private fun severityRank(value: String?): Int =
        when (value?.uppercase()) {
            "CRITICAL" -> SEVERITY_CRITICAL_RANK
            "HIGH" -> SEVERITY_HIGH_RANK
            "MEDIUM" -> SEVERITY_MEDIUM_RANK
            "LOW" -> SEVERITY_LOW_RANK
            else -> SEVERITY_UNKNOWN_RANK
        }
}
