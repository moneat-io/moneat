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

private const val SEVERITY_CRITICAL_RANK = 5
private const val SEVERITY_HIGH_RANK = 4
private const val SEVERITY_MEDIUM_RANK = 3
private const val SEVERITY_LOW_RANK = 2
private const val SEVERITY_INFO_RANK = 1
private const val SEVERITY_UNKNOWN_RANK = 0
private const val PRIORITY_P0_RANK = 6
private const val PRIORITY_P1_RANK = 5
private const val PRIORITY_P2_RANK = 4
private const val PRIORITY_P3_RANK = 3
private const val PRIORITY_P4_RANK = 2
private const val PRIORITY_P5_RANK = 1
private const val INCIDENT_SEV0_RANK = 5
private const val INCIDENT_SEV1_RANK = 4
private const val INCIDENT_SEV2_RANK = 3
private const val INCIDENT_SEV3_RANK = 2
private const val INCIDENT_SEV4_RANK = 1

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
                val expectedRank = rankedValue(resourceType, expected)
                expectedRank > 0 && rankedValue(resourceType, actual) >= expectedRank
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

    private fun rankedValue(
        resourceType: String?,
        value: String?
    ): Int =
        when (resourceType) {
            "AlertPriority" -> alertPriorityRank(value)
            "IncidentSeverity" -> incidentSeverityRank(value)
            "SecuritySeverity" -> securitySeverityRank(value)
            else -> SEVERITY_UNKNOWN_RANK
        }

    private fun alertPriorityRank(value: String?): Int =
        when (value?.uppercase()?.replace('_', '-')) {
            "P0" -> PRIORITY_P0_RANK
            "P1" -> PRIORITY_P1_RANK
            "P2" -> PRIORITY_P2_RANK
            "P3" -> PRIORITY_P3_RANK
            "P4" -> PRIORITY_P4_RANK
            "P5" -> PRIORITY_P5_RANK
            else -> SEVERITY_UNKNOWN_RANK
        }

    private fun incidentSeverityRank(value: String?): Int {
        val normalized = value?.uppercase()?.replace('_', '-') ?: return SEVERITY_UNKNOWN_RANK
        return when {
            normalized in setOf("SEV0", "SEV-0") -> INCIDENT_SEV0_RANK
            normalized in setOf("SEV1", "SEV-1") -> INCIDENT_SEV1_RANK
            normalized in setOf("SEV2", "SEV-2") -> INCIDENT_SEV2_RANK
            normalized in setOf("SEV3", "SEV-3") -> INCIDENT_SEV3_RANK
            normalized in setOf("SEV4", "SEV-4") -> INCIDENT_SEV4_RANK
            else -> SEVERITY_UNKNOWN_RANK
        }
    }

    private fun securitySeverityRank(value: String?): Int =
        when (value?.uppercase()) {
            "CRITICAL" -> SEVERITY_CRITICAL_RANK
            "HIGH" -> SEVERITY_HIGH_RANK
            "MEDIUM" -> SEVERITY_MEDIUM_RANK
            "LOW" -> SEVERITY_LOW_RANK
            "INFO" -> SEVERITY_INFO_RANK
            else -> SEVERITY_UNKNOWN_RANK
        }
}
