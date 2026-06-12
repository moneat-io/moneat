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

package com.moneat.services

import com.moneat.events.models.ProjectKeyResponse
import com.moneat.events.repositories.ProjectRepository
import com.moneat.events.repositories.models.ProjectRow
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.ProjectService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectServiceTest {

    private val projectRepository = mockk<ProjectRepository>()
    private val queryHelper = mockk<DashboardQueryHelper>()
    private val service = ProjectService(projectRepository, queryHelper)

    @Test
    fun `getProjects maps service identity fields`() = runBlocking {
        val row = projectRow(
            projectId = 42L,
            serviceId = 99L,
            serviceName = "checkout-api"
        )

        every { projectRepository.getProjectsForOrganizations(listOf(7)) } returns listOf(row)
        coEvery { queryHelper.getProjectRetentionDays(42L) } returns 30
        coEvery { projectRepository.getIssueCountForProject(42L, 30, 123L) } returns 4L

        val projects = service.getProjects(orgId = 7, demoEpochMs = 123L)

        assertEquals(1, projects.size)
        assertEquals(PROJECT_RESOURCE_ID, projects.single().id)
        assertEquals(PROJECT_RESOURCE_ID, projects.single().serviceId)
        assertEquals("checkout-api", projects.single().serviceName)
        assertEquals(4L, projects.single().issueCount)
    }

    @Test
    fun `getProject maps service identity fields`() = runBlocking {
        val row = projectRow(
            projectId = 42L,
            serviceId = 99L,
            serviceName = "checkout-api"
        )

        every { projectRepository.getProjectById(42L) } returns row
        coEvery { queryHelper.getProjectRetentionDays(42L) } returns 30
        coEvery { projectRepository.getIssueCountForProject(42L, 30, null) } returns 5L

        val project = service.getProject(42L)

        assertEquals(PROJECT_RESOURCE_ID, project?.id)
        assertEquals(PROJECT_RESOURCE_ID, project?.serviceId)
        assertEquals("checkout-api", project?.serviceName)
        assertEquals(5L, project?.issueCount)
    }

    @Test
    fun `project row service name defaults to non blank alias`() {
        val nameFallback = ProjectRow(
            projectId = 42L,
            name = "Checkout",
            slug = " ",
            framework = null,
            keys = emptyList(),
            dsn = "",
            resourceId = PROJECT_RESOURCE_ID
        )
        val idFallback = ProjectRow(
            projectId = 43L,
            name = " ",
            slug = " ",
            framework = null,
            keys = emptyList(),
            dsn = "",
            resourceId = PROJECT_RESOURCE_ID
        )

        assertEquals("Checkout", nameFallback.serviceName)
        assertEquals("43", idFallback.serviceName)
    }

    private fun projectRow(
        projectId: Long,
        serviceId: Long,
        serviceName: String
    ): ProjectRow =
        ProjectRow(
            projectId = projectId,
            name = "Checkout",
            slug = "checkout",
            framework = "kotlin",
            keys = listOf(ProjectKeyResponse(platformTarget = null, dsn = "https://public@example.com/42")),
            dsn = "https://public@example.com/42",
            resourceId = PROJECT_RESOURCE_ID,
            serviceId = serviceId,
            serviceName = serviceName
        )

    private companion object {
        private const val PROJECT_RESOURCE_ID = "018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"
    }
}
