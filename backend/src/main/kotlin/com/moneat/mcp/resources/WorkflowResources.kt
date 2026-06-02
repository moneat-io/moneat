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

package com.moneat.mcp.resources

import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpResource
import com.moneat.mcp.protocol.ResourceContent
import com.moneat.workflows.models.workflowJson
import com.moneat.workflows.services.WorkflowBlueprintCatalog
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val workflowResourceService = WorkflowService()
private val workflowResourceGovernanceService = WorkflowGovernanceService(workflowResourceService)

class WorkflowsOverviewResource(
    private val governanceService: WorkflowGovernanceService = workflowResourceGovernanceService,
) : McpResource {
    override val uri = "moneat://workflows/overview"
    override val name = "Workflows Overview"
    override val description = "Workflow counts, recent runs, success rate, and top workflows"
    override val requiredScopes = setOf(McpScopes.WORKFLOW_READ)

    override suspend fun read(context: McpContext): ResourceContent =
        ResourceContent(
            uri = uri,
            text = workflowJson.encodeToString(governanceService.overview(context.organizationId)),
        )
}

class WorkflowsUsageResource(
    private val governanceService: WorkflowGovernanceService = workflowResourceGovernanceService,
) : McpResource {
    override val uri = "moneat://workflows/usage"
    override val name = "Workflows Usage"
    override val description = "Workflow execution usage for the current billing period"
    override val requiredScopes = setOf(McpScopes.WORKFLOW_READ)

    override suspend fun read(context: McpContext): ResourceContent =
        ResourceContent(
            uri = uri,
            text = workflowJson.encodeToString(governanceService.usage(context.organizationId)),
        )
}

class WorkflowCatalogResource(
    private val workflowService: WorkflowService = workflowResourceService,
) : McpResource {
    override val uri = "moneat://workflows/catalog"
    override val name = "Workflow Catalog"
    override val description = "Workflow triggers, resources, actions, graph node types, and blueprints"
    override val requiredScopes = setOf(McpScopes.WORKFLOW_READ)

    override suspend fun read(context: McpContext): ResourceContent {
        val catalog = buildJsonObject {
            put("catalog", workflowJson.encodeToJsonElement(workflowService.catalog()))
            put("blueprints", workflowJson.encodeToJsonElement(WorkflowBlueprintCatalog.list()))
        }
        return ResourceContent(
            uri = uri,
            text = workflowJson.encodeToString(catalog),
        )
    }
}
