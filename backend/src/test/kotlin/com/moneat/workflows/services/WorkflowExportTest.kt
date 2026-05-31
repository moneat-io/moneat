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

package com.moneat.workflows.services

import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowStepConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowExportTest {
    private val interp = "$" + "{"
    private val interpEscaped = "$$" + "{"

    private fun workflow(name: String): WorkflowResponse =
        WorkflowResponse(
            id = 1,
            name = name,
            triggerName = "alert.triggered",
            enabled = true,
            version = 1,
            published = false,
            conditions = emptyList(),
            steps = listOf(WorkflowStepConfig("notification.slack", mapOf("message" to "hi"))),
            graph = WorkflowGraphConfig(),
            onceForTemplate = listOf("alert.deduplication_key"),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )

    @Test
    fun `export carries schema version and a source-neutral terraform resource`() {
        val export = WorkflowExport.toExport(workflow("Pager duty"))
        assertEquals(WorkflowExport.WORKFLOW_SCHEMA_VERSION, export.schemaVersion)
        assertTrue(export.terraform.contains("resource \"${WorkflowExport.TERRAFORM_RESOURCE_TYPE}\" \"pager_duty\""))
        assertEquals("alert.triggered", export.resource.triggerName)
    }

    @Test
    fun `terraform escapes template markers in user-controlled values`() {
        val name = "Alert ${interp}danger} %{x}"
        val terraform = WorkflowExport.toExport(workflow(name)).terraform
        // The literal "${" and "%{" must be escaped to "$${" and "%%{" so Terraform
        // does not interpret them as an interpolation / directive.
        assertTrue(terraform.contains("${interpEscaped}danger}"), "interpolation marker should be escaped")
        assertTrue(terraform.contains("%%{x}"), "directive marker should be escaped")
    }

    @Test
    fun `slug falls back when the name has no alphanumerics`() {
        val terraform = WorkflowExport.toExport(workflow("***")).terraform
        assertTrue(terraform.contains("\"workflow\""))
        assertFalse(terraform.contains("jsonencode("))
    }
}
