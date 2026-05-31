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

package com.moneat.security.detection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MitreCoverageServiceTest {

    @Test
    fun `coverage normalizes enabled rule technique and tactic tags`() {
        val response = MitreCoverageService().buildCoverage(
            listOf(
                CoverageRule(1, "Command execution", enabled = true, tags = listOf("mitre:t1059")),
                CoverageRule(2, "Brute force", enabled = true, tags = listOf("mitre:T1110", "mitre:TA0006")),
                CoverageRule(3, "No tag", enabled = true, tags = listOf("category:test")),
            ),
        )

        assertEquals(3, response.enabledRuleCount)
        assertEquals(listOf("T1059", "T1110"), response.techniques.map { it.techniqueId })
        assertTrue(response.tactics.any { it.tactic == "execution" && it.ruleCount == 1 })
        assertTrue(response.tactics.any { it.tactic == "credential-access" && it.ruleCount == 1 })
    }
}
