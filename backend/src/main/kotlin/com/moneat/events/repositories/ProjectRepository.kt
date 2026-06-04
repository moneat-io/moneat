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

package com.moneat.events.repositories

import com.moneat.events.models.UpdateProjectRequest
import com.moneat.events.repositories.models.ProjectRow

/**
 * Repository for project and project key data access.
 * Abstracts PostgreSQL (Exposed) and ClickHouse queries.
 */
interface ProjectRepository {
    fun getOrganizationIdsForUser(userId: Int): List<Int>
    fun getProjectsForOrganizations(orgIds: List<Int>): List<ProjectRow>
    fun getProjectById(projectId: Long): ProjectRow?
    fun getServiceNameForProject(projectId: Long): String?
    fun resolveServiceId(orgId: Int, serviceName: String, serviceNamespace: String = ""): Long?
    fun resolveServiceNames(projectIds: List<Long>): Map<Long, String>
    fun getProjectCountForOrganization(orgId: Int): Int
    fun findProjectByNameOrSlug(orgId: Int, name: String, slug: String): ProjectRow?
    fun createProject(orgId: Int, name: String, slug: String, framework: String?): Long
    fun createProjectKey(
        projectId: Long,
        publicKey: String,
        secretKey: String,
        platformTarget: String?
    )
    fun findProjectKeyByTarget(projectId: Long, target: String?): Boolean
    fun updateProject(projectId: Long, request: UpdateProjectRequest)
    fun deleteProject(projectId: Long)
    fun searchProjectsByName(orgId: Int, namePattern: String, limit: Int): List<ProjectRow>
    suspend fun getIssueCountForProject(
        projectId: Long,
        retentionDays: Int,
        demoEpochMs: Long?
    ): Long
}
