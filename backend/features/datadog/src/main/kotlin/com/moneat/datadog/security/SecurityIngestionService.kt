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

package com.moneat.datadog.security

import com.moneat.datadog.models.DdActivityDumpPayload
import com.moneat.datadog.models.DdCompliancePayload
import com.moneat.datadog.models.DdSecurityEventPayload
import com.moneat.security.signals.SignalOutcome

typealias QueuedSecurityBatch = com.moneat.security.ingestion.QueuedSecurityBatch
typealias QueuedSecurityEventEntry = com.moneat.security.ingestion.QueuedSecurityEventEntry
typealias QueuedActivityDumpEntry = com.moneat.security.ingestion.QueuedActivityDumpEntry
typealias QueuedComplianceEntry = com.moneat.security.ingestion.QueuedComplianceEntry

object SecurityIngestionService {
    fun enqueueSecurityEvents(
        orgId: Int,
        payload: DdSecurityEventPayload,
    ): Int {
        val now = System.currentTimeMillis()
        val entries = payload.events.map { event ->
            QueuedSecurityEventEntry(
                ruleId = event.ruleId,
                ruleName = event.ruleName,
                ruleCategory = event.ruleCategory,
                severity = event.severity,
                agentRuleVersion = event.agentRuleVersion,
                eventType = event.eventType,
                processName = event.processName,
                filePath = event.filePath,
                host = event.host,
                env = event.env,
                tags = parseDdTagList(event.tags),
                timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        com.moneat.security.ingestion.SecurityIngestionService.enqueueBatch(
            QueuedSecurityBatch(orgId, "events", events = entries)
        )
        return entries.size
    }

    fun enqueueActivityDumps(
        orgId: Int,
        payload: DdActivityDumpPayload,
    ): Int {
        val now = System.currentTimeMillis()
        val entries = payload.dumps.map { dump ->
            QueuedActivityDumpEntry(
                activityType = dump.activityType,
                processName = dump.processName,
                host = dump.host,
                durationNs = dump.durationNs,
                dumpData = dump.dumpData,
                tags = parseDdTagList(dump.tags),
                timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        com.moneat.security.ingestion.SecurityIngestionService.enqueueBatch(
            QueuedSecurityBatch(orgId, "dumps", dumps = entries)
        )
        return entries.size
    }

    fun enqueueCompliance(
        orgId: Int,
        payload: DdCompliancePayload,
    ): Int {
        val now = System.currentTimeMillis()
        val entries = payload.findings.map { finding ->
            QueuedComplianceEntry(
                framework = finding.framework,
                ruleId = finding.ruleId,
                ruleName = finding.ruleName,
                status = finding.status,
                resourceType = finding.resourceType,
                resourceId = finding.resourceId,
                resourceName = finding.resourceName,
                tags = parseDdTagList(finding.tags),
                timestampMs = now,
            )
        }
        if (entries.isEmpty()) return 0
        com.moneat.security.ingestion.SecurityIngestionService.enqueueBatch(
            QueuedSecurityBatch(orgId, "findings", findings = entries)
        )
        return entries.size
    }

    suspend fun insertBatch(batch: QueuedSecurityBatch): List<SignalOutcome> =
        com.moneat.security.ingestion.SecurityIngestionService.insertBatch(batch)

    fun decodeBatch(encoded: String): QueuedSecurityBatch =
        com.moneat.security.ingestion.SecurityIngestionService.decodeBatch(encoded)

    internal fun parseDdTagList(tags: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        tags.forEach { tag ->
            val colonIdx = tag.indexOf(':')
            if (colonIdx > 0) {
                result[tag.substring(0, colonIdx)] = tag.substring(colonIdx + 1)
            } else if (tag.isNotEmpty()) {
                result[tag] = ""
            }
        }
        return result
    }
}
