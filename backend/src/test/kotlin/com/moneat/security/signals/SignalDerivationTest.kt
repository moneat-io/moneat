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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalDerivationTest {

    @Test
    fun `runtime event derives a single agent_runtime spec keyed by rule host process`() {
        val batch = QueuedSecurityBatch(
            organizationId = 1,
            batchType = "events",
            events = listOf(
                QueuedSecurityEventEntry(
                    ruleId = "cws-1",
                    ruleName = "Suspicious exec",
                    severity = "high",
                    processName = "bash",
                    filePath = "/etc/shadow",
                    host = "web-01",
                    timestampMs = 1_700_000_000_000
                )
            )
        )

        val specs = SignalDerivation.fromBatch(batch)

        assertEquals(1, specs.size)
        val spec = specs.single()
        assertEquals(SignalSource.AGENT_RUNTIME, spec.source)
        assertEquals("cws-1|web-01|bash", spec.dedupKey)
        assertEquals(SignalSeverity.HIGH, spec.severity)
        assertEquals("web-01", spec.entities["host"])
        assertEquals("bash", spec.entities["process"])
        assertEquals("/etc/shadow", spec.entities["resource"])
        assertTrue(spec.evidenceReference.contains("table=security_events"))
        assertTrue(spec.evidenceReference.contains("cws-1"))
    }

    @Test
    fun `failed compliance finding derives an agent_compliance spec keyed by rule resource`() {
        val batch = QueuedSecurityBatch(
            organizationId = 1,
            batchType = "findings",
            findings = listOf(
                QueuedComplianceEntry(
                    framework = "cis-aws",
                    ruleId = "cis-1.1",
                    ruleName = "Root MFA",
                    status = "failed",
                    resourceType = "aws_account",
                    resourceId = "acct-123",
                    resourceName = "prod",
                    timestampMs = 1_700_000_000_000
                )
            )
        )

        val specs = SignalDerivation.fromBatch(batch)

        assertEquals(1, specs.size)
        val spec = specs.single()
        assertEquals(SignalSource.AGENT_COMPLIANCE, spec.source)
        assertEquals("cis-1.1|acct-123", spec.dedupKey)
        assertEquals(SignalSeverity.HIGH, spec.severity)
        assertEquals("prod", spec.entities["resource"])
        assertEquals("acct-123", spec.entities["resource_id"])
        assertTrue(spec.evidenceReference.contains("table=compliance_findings"))
    }

    @Test
    fun `passed and skipped compliance findings derive no signals`() {
        val batch = QueuedSecurityBatch(
            organizationId = 1,
            batchType = "findings",
            findings = listOf(
                QueuedComplianceEntry(ruleId = "r1", status = "passed", timestampMs = 1),
                QueuedComplianceEntry(ruleId = "r2", status = "skipped", timestampMs = 1)
            )
        )

        assertTrue(SignalDerivation.fromBatch(batch).isEmpty())
    }

    @Test
    fun `error compliance finding maps to medium severity`() {
        val batch = QueuedSecurityBatch(
            organizationId = 1,
            batchType = "findings",
            findings = listOf(
                QueuedComplianceEntry(ruleId = "r3", status = "error", resourceId = "x", timestampMs = 1)
            )
        )

        assertEquals(SignalSeverity.MEDIUM, SignalDerivation.fromBatch(batch).single().severity)
    }

    @Test
    fun `activity dump batches derive no signals`() {
        val batch = QueuedSecurityBatch(organizationId = 1, batchType = "dumps")
        assertTrue(SignalDerivation.fromBatch(batch).isEmpty())
    }
}
