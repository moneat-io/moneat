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

package com.moneat.security.signals

import com.moneat.datadog.security.QueuedComplianceEntry
import com.moneat.datadog.security.QueuedSecurityBatch
import com.moneat.datadog.security.QueuedSecurityEventEntry

private const val COMPLIANCE_STATUS_FAILED = "failed"
private const val COMPLIANCE_STATUS_ERROR = "error"
private const val EVIDENCE_TYPE = "clickhouse_query"
private const val EVENTS_TABLE = "security_events"
private const val COMPLIANCE_TABLE = "compliance_findings"

/**
 * Turns an ingested agent batch into the signal specs the agent passthrough produces. A CWS runtime
 * event yields one `agent_runtime` spec (dedup by rule + host + process); a **failed** compliance
 * finding yields one `agent_compliance` spec (dedup by rule + resource). Passed/skipped findings and
 * activity dumps produce nothing. Evidence is a ClickHouse query descriptor — never the rows.
 */
object SignalDerivation {

    fun fromBatch(batch: QueuedSecurityBatch): List<SignalSpec> =
        when (batch.batchType) {
            "events" -> batch.events.map { runtimeSpec(it) }
            "findings" -> batch.findings.mapNotNull { complianceSpec(it) }
            else -> emptyList()
        }

    private fun runtimeSpec(event: QueuedSecurityEventEntry): SignalSpec {
        val host = event.host
        val process = event.processName
        val resource = event.filePath.ifBlank { process.ifBlank { host } }
        val entities = buildMap {
            if (host.isNotBlank()) put("host", host)
            if (process.isNotBlank()) put("process", process)
            if (resource.isNotBlank()) put("resource", resource)
        }
        val dedupKey = "${event.ruleId}|$host|$process"
        return SignalSpec(
            source = SignalSource.AGENT_RUNTIME,
            ruleId = event.ruleId,
            ruleName = event.ruleName.ifBlank { event.ruleId },
            severity = SignalSeverity.fromWire(event.severity),
            dedupKey = dedupKey,
            entities = entities,
            evidenceType = EVIDENCE_TYPE,
            evidenceReference = runtimeEvidence(event, dedupKey),
            occurredAtMs = event.timestampMs,
        )
    }

    private fun complianceSpec(finding: QueuedComplianceEntry): SignalSpec? {
        val severity = complianceSeverity(finding.status) ?: return null
        val resourceId = finding.resourceId
        val resourceName = finding.resourceName.ifBlank { resourceId }
        val entities = buildMap {
            if (resourceName.isNotBlank()) put("resource", resourceName)
            if (resourceId.isNotBlank()) put("resource_id", resourceId)
            if (finding.framework.isNotBlank()) put("framework", finding.framework)
        }
        val dedupKey = "${finding.ruleId}|$resourceId"
        return SignalSpec(
            source = SignalSource.AGENT_COMPLIANCE,
            ruleId = finding.ruleId,
            ruleName = finding.ruleName.ifBlank { finding.ruleId },
            severity = severity,
            dedupKey = dedupKey,
            entities = entities,
            evidenceType = EVIDENCE_TYPE,
            evidenceReference = complianceEvidence(finding, dedupKey),
            occurredAtMs = finding.timestampMs,
        )
    }

    /** Compliance status → signal severity. Only failing states become signals. */
    private fun complianceSeverity(status: String): SignalSeverity? =
        when (status) {
            COMPLIANCE_STATUS_FAILED -> SignalSeverity.HIGH
            COMPLIANCE_STATUS_ERROR -> SignalSeverity.MEDIUM
            else -> null
        }

    private fun runtimeEvidence(event: QueuedSecurityEventEntry, dedupKey: String): String =
        "table=$EVENTS_TABLE dedup=$dedupKey rule_id=${event.ruleId} " +
            "host=${event.host} process=${event.processName} occurred_at_ms=${event.timestampMs}"

    private fun complianceEvidence(finding: QueuedComplianceEntry, dedupKey: String): String =
        "table=$COMPLIANCE_TABLE dedup=$dedupKey rule_id=${finding.ruleId} " +
            "resource_id=${finding.resourceId} occurred_at_ms=${finding.timestampMs}"
}
