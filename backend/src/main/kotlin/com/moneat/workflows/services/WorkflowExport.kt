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

import com.moneat.workflows.models.WorkflowExportResponse
import com.moneat.workflows.models.WorkflowGraphResource
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.workflowJson
import kotlinx.serialization.encodeToString

/**
 * Stable, source-neutral export/import schema for workflows. Version 1 captures
 * the trigger, enabled flag, derived graph, and once-for template so a workflow
 * can be round-tripped (export → import → export) without drift. A Terraform
 * HCL rendering is produced alongside the JSON resource for infrastructure-as-code
 * users; the graph is embedded via `jsonencode` so it stays byte-stable.
 */
object WorkflowExport {
    const val WORKFLOW_SCHEMA_VERSION = 1
    const val TERRAFORM_RESOURCE_TYPE = "moneat_workflow"

    private val json = workflowJson
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun toExport(workflow: WorkflowResponse): WorkflowExportResponse {
        val resource =
            WorkflowGraphResource(
                name = workflow.name,
                triggerName = workflow.triggerName,
                enabled = workflow.enabled,
                graph = workflow.graph,
                onceForTemplate = workflow.onceForTemplate
            )
        return WorkflowExportResponse(
            schemaVersion = WORKFLOW_SCHEMA_VERSION,
            resource = resource,
            terraform = terraform(resource)
        )
    }

    fun slug(name: String): String =
        name
            .lowercase()
            .replace(NON_ALNUM, "_")
            .trim('_')
            .ifBlank { "workflow" }

    private fun terraform(resource: WorkflowGraphResource): String {
        // The graph travels as its canonical JSON string (the same shape the API accepts),
        // emitted as a plain HCL string attribute. Wrapping it in jsonencode() would
        // double-encode an already-serialized JSON string, so it is passed through directly.
        val graphJson = json.encodeToString(resource.graph)
        val onceForHcl = resource.onceForTemplate.joinToString(", ") { item -> item.hclString() }
        return buildString {
            appendLine("""resource "$TERRAFORM_RESOURCE_TYPE" "${slug(resource.name)}" {""")
            appendLine("""  name              = ${resource.name.hclString()}""")
            appendLine("""  trigger_name      = ${resource.triggerName.hclString()}""")
            appendLine("""  enabled           = ${resource.enabled}""")
            appendLine("""  once_for_template = [$onceForHcl]""")
            appendLine("""  graph             = ${graphJson.hclString()}""")
            append("}")
        }
    }

    // Terraform template markers, built via concatenation to avoid Kotlin string-template parsing.
    private const val HCL_INTERP = "$" + "{"
    private const val HCL_INTERP_ESCAPED = "$$" + "{"
    private const val HCL_DIRECTIVE = "%{"
    private const val HCL_DIRECTIVE_ESCAPED = "%%{"

    private fun String.hclString(): String =
        "\"" +
            replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                // Escape Terraform template markers so literal `${` / `%{` in user-controlled
                // values are not interpreted as interpolations/directives.
                .replace(HCL_INTERP, HCL_INTERP_ESCAPED)
                .replace(HCL_DIRECTIVE, HCL_DIRECTIVE_ESCAPED) +
            "\""
}
