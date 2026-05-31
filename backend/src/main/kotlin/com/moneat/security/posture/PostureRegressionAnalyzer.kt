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

package com.moneat.security.posture

import com.moneat.security.signals.SignalSeverity
import com.moneat.security.signals.SignalSource
import com.moneat.security.signals.SignalSpec
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant

private const val COMPLIANCE_TABLE = "compliance_findings"
private const val EVIDENCE_TYPE = "clickhouse_query"

object PostureRegressionAnalyzer {

    fun analyze(organizationId: Int, findings: List<ComplianceFindingInput>): List<SignalSpec> {
        if (findings.isEmpty()) return emptyList()
        return transaction {
            findings.mapNotNull { finding ->
                val status = ComplianceFindingStatus.normalize(finding.status)
                val occurred = occurrenceTime(finding.timestampMs)
                val previous = upsertState(organizationId, finding, status, occurred)
                if (previous == ComplianceFindingStatus.PASSED && status == ComplianceFindingStatus.FAILED) {
                    regressionSignal(finding)
                } else {
                    null
                }
            }
        }
    }

    private fun upsertState(
        organizationId: Int,
        finding: ComplianceFindingInput,
        status: ComplianceFindingStatus,
        occurred: Instant,
    ): ComplianceFindingStatus? {
        val existing = SecurityComplianceFindingStates
            .selectAll()
            .where {
                (SecurityComplianceFindingStates.organizationId eq organizationId) and
                    (SecurityComplianceFindingStates.framework eq finding.framework) and
                    (SecurityComplianceFindingStates.ruleId eq finding.ruleId) and
                    (SecurityComplianceFindingStates.resourceType eq finding.resourceType) and
                    (SecurityComplianceFindingStates.resourceId eq finding.resourceId)
            }
            .forUpdate()
            .firstOrNull()
        val now = Clock.System.now()
        if (existing == null) {
            SecurityComplianceFindingStates.insert {
                it[SecurityComplianceFindingStates.organizationId] = organizationId
                it[framework] = finding.framework
                it[ruleId] = finding.ruleId
                it[ruleName] = finding.ruleName
                it[resourceType] = finding.resourceType
                it[resourceId] = finding.resourceId
                it[resourceName] = finding.resourceName
                it[SecurityComplianceFindingStates.status] = status.wire
                it[firstSeen] = occurred
                it[lastSeen] = occurred
                it[lastRegressedAt] = null
                it[updatedAt] = now
            }
            return null
        }

        val previous = ComplianceFindingStatus.normalize(existing[SecurityComplianceFindingStates.status])
        val stateId = existing[SecurityComplianceFindingStates.id].value
        SecurityComplianceFindingStates.update({ SecurityComplianceFindingStates.id eq stateId }) {
            it[ruleName] = finding.ruleName
            it[resourceName] = finding.resourceName
            it[SecurityComplianceFindingStates.status] = status.wire
            it[firstSeen] = minOf(existing[SecurityComplianceFindingStates.firstSeen], occurred)
            it[lastSeen] = maxOf(existing[SecurityComplianceFindingStates.lastSeen], occurred)
            if (previous == ComplianceFindingStatus.PASSED && status == ComplianceFindingStatus.FAILED) {
                it[lastRegressedAt] = occurred
            }
            it[updatedAt] = now
        }
        return previous
    }

    private fun regressionSignal(finding: ComplianceFindingInput): SignalSpec {
        val key = findingKey(finding)
        val resourceName = finding.resourceName.ifBlank { finding.resourceId }
        val entities = buildMap {
            if (finding.framework.isNotBlank()) put("framework", finding.framework)
            if (finding.resourceType.isNotBlank()) put("resource_type", finding.resourceType)
            if (resourceName.isNotBlank()) put("resource", resourceName)
            if (finding.resourceId.isNotBlank()) put("resource_id", finding.resourceId)
            put("finding_key", key)
        }
        return SignalSpec(
            source = SignalSource.AGENT_COMPLIANCE,
            ruleId = finding.ruleId,
            ruleName = finding.ruleName.ifBlank { finding.ruleId },
            severity = SignalSeverity.HIGH,
            dedupKey = key,
            entities = entities,
            evidenceType = EVIDENCE_TYPE,
            evidenceReference = "table=$COMPLIANCE_TABLE finding=$key rule_id=${finding.ruleId} " +
                "resource_id=${finding.resourceId} previous=passed status=failed " +
                "occurred_at_ms=${finding.timestampMs}",
            occurredAtMs = finding.timestampMs,
            tags = listOf("posture:regression"),
        )
    }

    private fun findingKey(finding: ComplianceFindingInput): String =
        listOf(finding.framework, finding.ruleId, finding.resourceType, finding.resourceId)
            .joinToString("|")

    private fun occurrenceTime(timestampMs: Long): Instant {
        val occurred = Instant.fromEpochMilliseconds(timestampMs)
        val now = Clock.System.now()
        return if (occurred > now) now else occurred
    }
}
