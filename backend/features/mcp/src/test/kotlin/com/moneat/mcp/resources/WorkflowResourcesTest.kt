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
    // ──── Context ────
    private val context = McpContext(
        organizationId = ORGANIZATION_ID,
        userId = USER_ID,
        tokenId = TOKEN_ID,
        scopes = setOf(McpScopes.WORKFLOW_READ),
        sessionId = "workflow-resource-test",
    )

    // ──── Tests ────
    @Test
    fun `workflow resources serialize overview usage and catalog data`() = runBlocking {
        val governanceService = mockk<WorkflowGovernanceService>()
        val workflowService = mockk<WorkflowService>()
        every { governanceService.overview(ORGANIZATION_ID, any()) } returns workflowOverview()
        every { governanceService.usage(ORGANIZATION_ID, any()) } returns workflowUsage()
        every { workflowService.catalog() } returns WorkflowCatalog.response()

        val overview = WorkflowsOverviewResource(governanceService).read(context)
        val usage = WorkflowsUsageResource(governanceService).read(context)
        val catalog = WorkflowCatalogResource(workflowService).read(context)

        assertEquals("moneat://workflows/overview", overview.uri)
        assertTrue(overview.text.orEmpty().contains("total_workflows"))
        assertTrue(usage.text.orEmpty().contains("2026-06"))
        assertTrue(catalog.text.orEmpty().contains("blueprints"))
    }

    // ──── Fixtures ────
    private fun workflowOverview(): WorkflowOverviewResponse =
        WorkflowOverviewResponse(
            totalWorkflows = TOTAL_WORKFLOWS,
            enabledWorkflows = ENABLED_WORKFLOWS,
            publishedWorkflows = PUBLISHED_WORKFLOWS,
            runsLast30d = RUNS_LAST_30D,
            successRate = SUCCESS_RATE,
            failedLast30d = FAILED_LAST_30D,
        )

    private fun workflowUsage(): WorkflowUsageResponse =
        WorkflowUsageResponse(
            period = "2026-06",
            used = WORKFLOW_USAGE_USED,
            limit = WORKFLOW_USAGE_LIMIT,
            remaining = WORKFLOW_USAGE_REMAINING,
            unlimited = false,
        )

    // ──── Constants ────
    private companion object {
        private const val ORGANIZATION_ID = 7
        private const val USER_ID = 42
        private const val TOKEN_ID = 99
        private const val TOTAL_WORKFLOWS = 3L
        private const val ENABLED_WORKFLOWS = 2L
        private const val PUBLISHED_WORKFLOWS = 1L
        private const val RUNS_LAST_30D = 10L
        private const val SUCCESS_RATE = 90.0
        private const val FAILED_LAST_30D = 1L
        private const val WORKFLOW_USAGE_USED = 10L
        private const val WORKFLOW_USAGE_LIMIT = 100
        private const val WORKFLOW_USAGE_REMAINING = 90
    }
}
