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

package com.moneat.events.services

import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.PricingTierService
import com.moneat.events.models.CreateProjectRequest
import com.moneat.events.models.ProjectKeyResponse
import com.moneat.events.models.ProjectResponse
import com.moneat.events.models.UpdateProjectRequest
import com.moneat.events.repositories.ProjectRepository
import java.security.SecureRandom
import java.util.Base64

class ProjectService(
    private val projectRepository: ProjectRepository,
    private val queryHelper: DashboardQueryHelper,
    private val pricingTierService: PricingTierService = PricingTierService(),
    private val billingQuotaService: BillingQuotaService = BillingQuotaService(),
) {

    companion object {
        private const val KEY_BYTE_LENGTH = 32
        private const val PUBLIC_KEY_LENGTH = 40
    }

    suspend fun getProjects(
        userId: Int,
        demoEpochMs: Long? = null
    ): List<ProjectResponse> {
        val orgIds = projectRepository.getOrganizationIdsForUser(userId)
        if (orgIds.isEmpty()) return emptyList()

        val projects = projectRepository.getProjectsForOrganizations(orgIds)

        return projects.map { row ->
            val issueCount = projectRepository.getIssueCountForProject(
                row.projectId,
                queryHelper.getProjectRetentionDays(row.projectId),
                demoEpochMs
            )
            ProjectResponse(
                id = row.projectId,
                resourceId = row.resourceId,
                name = row.name,
                slug = row.slug,
                serviceId = row.serviceId,
                serviceName = row.serviceName,
                framework = row.framework,
                keys = row.keys,
                dsn = row.dsn,
                issueCount = issueCount
            )
        }
    }

    suspend fun getProject(projectId: Long): ProjectResponse? {
        val row = projectRepository.getProjectById(projectId) ?: return null
        val issueCount = projectRepository.getIssueCountForProject(
            projectId,
            queryHelper.getProjectRetentionDays(projectId),
            null
        )
        return ProjectResponse(
            id = row.projectId,
            resourceId = row.resourceId,
            name = row.name,
            slug = row.slug,
            serviceId = row.serviceId,
            serviceName = row.serviceName,
            framework = row.framework,
            keys = row.keys,
            dsn = row.dsn,
            issueCount = issueCount
        )
    }

    suspend fun createProject(
        userId: Int,
        request: CreateProjectRequest
    ): ProjectResponse {
        val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId)
            ?: throw IllegalStateException("User has no organization")

        if (billingQuotaService.isEnforcementEnabled()) {
            val tier = pricingTierService.getEffectiveTierForOrganization(orgId).tier
            tier.maxProjects?.let { max ->
                val currentCount = projectRepository.getProjectCountForOrganization(orgId)
                check(currentCount < max) { "project_limit_reached" }
            }
        }

        val slug = normalizeSlug(request.name)
        val existing = projectRepository.findProjectByNameOrSlug(orgId, request.name, slug)
        check(existing == null) { "A project with this name already exists" }

        val projectId = projectRepository.createProject(orgId, request.name, slug, request.framework)

        val targets = request.targets?.filter { it.isNotBlank() }?.distinct() ?: listOf(null)
        for (target in targets) {
            val exists = projectRepository.findProjectKeyByTarget(projectId, target)
            check(!exists) { "Target $target already exists" }
            val publicKey = generatePublicKey()
            val secretKey = generateSecretKey()
            projectRepository.createProjectKey(projectId, publicKey, secretKey, target)
        }

        return getProject(projectId)
            ?: throw IllegalStateException("Project not found after creation (id=$projectId)")
    }

    fun addProjectTarget(
        projectId: Long,
        target: String
    ): ProjectKeyResponse {
        val exists = projectRepository.findProjectKeyByTarget(projectId, target)
        check(!exists) { "Target $target already exists" }

        val publicKey = generatePublicKey()
        val secretKey = generateSecretKey()
        projectRepository.createProjectKey(projectId, publicKey, secretKey, target)

        val backendUrl = com.moneat.config.EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        val host = backendUrl.removePrefix("http://").removePrefix("https://")
        val scheme = if (backendUrl.startsWith("https")) "https" else "http"
        val dsn = "$scheme://$publicKey@$host/$projectId"

        return ProjectKeyResponse(
            platformTarget = target,
            dsn = dsn
        )
    }

    fun updateProject(
        projectId: Long,
        request: UpdateProjectRequest
    ) {
        projectRepository.updateProject(projectId, request)
    }

    fun deleteProject(projectId: Long) {
        projectRepository.deleteProject(projectId)
    }

    private fun normalizeSlug(name: String): String {
        return name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "project" }
    }

    private fun generatePublicKey(): String {
        val bytes = ByteArray(KEY_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(PUBLIC_KEY_LENGTH)
    }

    private fun generateSecretKey(): String {
        val bytes = ByteArray(KEY_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
