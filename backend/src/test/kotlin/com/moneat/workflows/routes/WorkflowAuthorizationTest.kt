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

package com.moneat.workflows.routes

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowAuthorizationTest {

    @Test
    fun `granular allow wins without consulting the coarse gate`() {
        var coarseConsulted = false
        val allowed = resolveWorkflowAccess(granular = true) {
            coarseConsulted = true
            false
        }
        assertTrue(allowed)
        assertFalse(coarseConsulted, "coarse gate must not run once the bridge decides")
    }

    @Test
    fun `granular deny wins without consulting the coarse gate`() {
        var coarseConsulted = false
        val allowed = resolveWorkflowAccess(granular = false) {
            coarseConsulted = true
            true
        }
        assertFalse(allowed)
        assertFalse(coarseConsulted, "coarse gate must not run once the bridge decides")
    }

    @Test
    fun `falls back to the coarse gate when the bridge cannot decide`() {
        assertTrue(resolveWorkflowAccess(granular = null) { true })
        assertFalse(resolveWorkflowAccess(granular = null) { false })
    }
}
