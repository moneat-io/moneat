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

import com.moneat.workflows.engine.temporal.HTTP_REQUEST_ACTION
import com.moneat.workflows.engine.temporal.TRANSFORM_GRAALJS_ACTION
import com.moneat.workflows.engine.temporal.WORKFLOWS_EGRESS_ENABLED_ENV
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowCatalogTest {
    private var previousEgressValue: String? = null

    @BeforeTest
    fun snapshotEgressFlag() {
        previousEgressValue = System.getProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
    }

    @AfterTest
    fun restoreEgressFlag() {
        previousEgressValue?.let { value ->
            System.setProperty(WORKFLOWS_EGRESS_ENABLED_ENV, value)
        } ?: System.clearProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
    }

    @Test
    fun `hides egress actions when the egress feature is disabled`() {
        System.clearProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
        val names = WorkflowCatalog.response().steps.map { it.name }
        assertFalse(names.contains(HTTP_REQUEST_ACTION))
        assertFalse(names.contains(TRANSFORM_GRAALJS_ACTION))
    }

    @Test
    fun `exposes egress actions when the egress feature is enabled`() {
        System.setProperty(WORKFLOWS_EGRESS_ENABLED_ENV, "true")
        val names = WorkflowCatalog.response().steps.map { it.name }
        assertTrue(names.contains(HTTP_REQUEST_ACTION))
        assertTrue(names.contains(TRANSFORM_GRAALJS_ACTION))
    }

    @Test
    fun `incident role trigger exposes role context and action deduplication`() {
        val trigger = requireNotNull(WorkflowCatalog.trigger("incident.role_changed"))

        assertEquals("When an incident role changes", trigger.label)
        assertEquals(
            listOf("incident.id", "incident.role_action", "incident.role_event_id"),
            trigger.defaultOnceForTemplate,
        )
        assertTrue(trigger.scope.any { it.name == "incident.role" && it.type == "String" })
        assertTrue(trigger.scope.any { it.name == "incident.assignee" && it.type == "String" })
        assertTrue(trigger.scope.any { it.name == "incident.role_event_id" && it.type == "String" })
    }
}
