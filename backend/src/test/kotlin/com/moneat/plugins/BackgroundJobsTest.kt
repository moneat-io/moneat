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

package com.moneat.plugins

import com.moneat.runtime.MoneatProcessRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackgroundJobsTest {

    @Test
    fun `workflow worker mode defaults to trusted when unset or blank`() {
        assertEquals(WorkflowWorkerMode.TRUSTED, parseWorkflowWorkerMode(null))
        assertEquals(WorkflowWorkerMode.TRUSTED, parseWorkflowWorkerMode("  "))
    }

    @Test
    fun `workflow worker mode accepts configured enum names case insensitively`() {
        assertEquals(WorkflowWorkerMode.ALL, parseWorkflowWorkerMode("all"))
        assertEquals(WorkflowWorkerMode.EGRESS, parseWorkflowWorkerMode(" egress "))
        assertEquals(WorkflowWorkerMode.NONE, parseWorkflowWorkerMode("NONE"))
    }

    @Test
    fun `workflow worker mode rejects invalid values`() {
        val error = assertFailsWith<IllegalArgumentException> {
            parseWorkflowWorkerMode("egres")
        }

        assertTrue(error.message.orEmpty().contains("EGRES"))
        assertTrue(error.message.orEmpty().contains("trusted"))
    }

    @Test
    fun `process role parser accepts deployable roles`() {
        assertEquals(MoneatProcessRole.ALL, MoneatProcessRole.from(null))
        assertEquals(MoneatProcessRole.API, MoneatProcessRole.from("api-only"))
        assertEquals(MoneatProcessRole.SCHEDULER, MoneatProcessRole.from("schedulers"))
        assertEquals(MoneatProcessRole.INGESTION_WORKER, MoneatProcessRole.from("ingestion-worker"))
        assertEquals(MoneatProcessRole.WORKFLOW_EGRESS, MoneatProcessRole.from("egress"))
    }

    @Test
    fun `process role parser rejects invalid values`() {
        val error = assertFailsWith<IllegalArgumentException> {
            MoneatProcessRole.from("web")
        }

        assertTrue(error.message.orEmpty().contains("MONEAT_PROCESS_ROLE"))
    }
}
