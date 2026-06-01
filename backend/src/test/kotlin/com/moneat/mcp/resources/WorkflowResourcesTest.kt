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
import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.models.WorkflowOverviewResponse
import com.moneat.workflows.models.WorkflowUsageResponse
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowResourcesTest {
    private val context = McpContext(
        organizationId = 7,
        userId = 42,
        tokenId = 99,
        scopes = setOf(McpScopes.WORKFLOW_READ),
        sessionId = "workflow-resource-test",
    )

    @Test
    fun `workflow resources serialize overview usage and catalog data`() = runBlocking {
        val governanceService = mockk<WorkflowGovernanceService>()
        val workflowService = mockk<WorkflowService>()
        every { governanceService.overview(7, any()) } returns workflowOverview()
        every { governanceService.usage(7, any()) } returns workflowUsage()
        every { workflowService.catalog() } returns WorkflowCatalog.response()

        val overview = WorkflowsOverviewResource(governanceService).read(context)
        val usage = WorkflowsUsageResource(governanceService).read(context)
        val catalog = WorkflowCatalogResource(workflowService).read(context)

        assertEquals("moneat://workflows/overview", overview.uri)
        assertTrue(overview.text.orEmpty().contains("total_workflows"))
        assertTrue(usage.text.orEmpty().contains("2026-06"))
        assertTrue(catalog.text.orEmpty().contains("blueprints"))
    }

    private fun workflowOverview(): WorkflowOverviewResponse =
        WorkflowOverviewResponse(
            totalWorkflows = 3,
            enabledWorkflows = 2,
            publishedWorkflows = 1,
            runsLast30d = 10,
            successRate = 90.0,
            failedLast30d = 1,
        )

    private fun workflowUsage(): WorkflowUsageResponse =
        WorkflowUsageResponse(
            period = "2026-06",
            used = 10,
            limit = 100,
            remaining = 90,
            unlimited = false,
        )
}
