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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalDerivationTest {

    @Test
    fun `runtime event derives a single agent_runtime spec keyed by rule host process`() {
        val events = listOf(
            RuntimeSecurityEventInput(
                ruleId = "cws-1",
                ruleName = "Suspicious exec",
                severity = "high",
                processName = "bash",
                filePath = "/etc/shadow",
                host = "web-01",
                timestampMs = 1_700_000_000_000
            )
        )

        val specs = SignalDerivation.fromRuntimeEvents(events)

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
    fun `empty runtime event input derives no signals`() {
        assertTrue(SignalDerivation.fromRuntimeEvents(emptyList()).isEmpty())
    }
}
