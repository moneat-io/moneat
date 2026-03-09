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
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.events.models.CreateProjectRequest
import com.moneat.events.models.ProjectKeyResponse
import com.moneat.events.models.ProjectResponse
import com.moneat.events.models.UpdateProjectRequest
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.utils.ClickHouseQueryUtils
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.*

private val logger = KotlinLogging.logger {}

class ProjectService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val pricingTierService = PricingTierService()
    private val billingQuotaService = BillingQuotaService()

    suspend fun getProjects(
        userId: Int,
        demoEpochMs: Long? = null
    ): List<ProjectResponse> {
        val orgIds = transaction {
            Memberships
                .selectAll()
                .where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }
                .distinct()
        }
        if (orgIds.isEmpty()) return emptyList()

        data class ProjectRow(
            val projectId: Long,
            val name: String,
            val slug: String,
            val framework: String?,
            val keys: List<ProjectKeyResponse>,
            val dsn: String
        )

        val projects = transaction {
            Projects
                .selectAll()
                .where { Projects.organization_id inList orgIds }
                .map { row ->
                    val projectId = row[Projects.id]
                    val keys = ProjectKeys
                        .selectAll()
                        .where { (ProjectKeys.project_id eq projectId) and (ProjectKeys.is_active eq true) }
                        .map { k ->
                            val pubKey = k[ProjectKeys.public_key]
                            ProjectKeyResponse(
                                platformTarget = k[ProjectKeys.platform_target],
                                dsn = buildDsn(pubKey, projectId)
                            )
                        }
                    val firstDsn = keys.firstOrNull()?.dsn ?: ""
                    ProjectRow(
                        projectId = projectId,
                        name = row[Projects.name],
                        slug = row[Projects.slug],
                        framework = row[Projects.framework],
                        keys = keys,
                        dsn = firstDsn
                    )
                }
        }

        return projects.map { (projectId, name, slug, framework, keys, dsn) ->
            val issueCount = getIssueCountForProject(projectId, demoEpochMs)
            ProjectResponse(
                id = projectId,
                name = name,
                slug = slug,
                framework = framework,
                keys = keys,
                dsn = dsn,
                issueCount = issueCount
            )
        }
    }

    suspend fun getProject(projectId: Long): ProjectResponse? {
        val row = transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
        } ?: return null

        val keys = transaction {
            ProjectKeys
                .selectAll()
                .where { (ProjectKeys.project_id eq projectId) and (ProjectKeys.is_active eq true) }
                .map { k ->
                    ProjectKeyResponse(
                        platformTarget = k[ProjectKeys.platform_target],
                        dsn = buildDsn(k[ProjectKeys.public_key], projectId)
                    )
                }
        }
        val firstDsn = keys.firstOrNull()?.dsn ?: ""
        val issueCount = getIssueCountForProject(projectId, null)

        return ProjectResponse(
            id = projectId,
            name = row[Projects.name],
            slug = row[Projects.slug],
            framework = row[Projects.framework],
            keys = keys,
            dsn = firstDsn,
            issueCount = issueCount
        )
    }

    fun createProject(
        userId: Int,
        request: CreateProjectRequest
    ): ProjectResponse {
        val orgId = pricingTierService.getPrimaryOrganizationIdForUser(userId)
            ?: throw IllegalStateException("User has no organization")

        if (billingQuotaService.isEnforcementEnabled()) {
            val tier = pricingTierService.getEffectiveTierForOrganization(orgId).tier
            tier.maxProjects?.let { max ->
                val currentCount = transaction {
                    Projects.selectAll().where { Projects.organization_id eq orgId }.count()
                }
                check(currentCount < max) { "project_limit_reached" }
            }
        }

        val slug = normalizeSlug(request.name)
        val existing = transaction {
            Projects
                .selectAll()
                .where {
                    (Projects.organization_id eq orgId) and
                        ((Projects.name eq request.name) or (Projects.slug eq slug))
                }
                .firstOrNull()
        }
        check(existing == null) { "A project with this name already exists" }

        val projectId = transaction {
            Projects.insert {
                it[Projects.organization_id] = orgId
                it[Projects.name] = request.name
                it[Projects.slug] = slug
                it[Projects.framework] = request.framework
            }[Projects.id]
        }

        val targets = request.targets?.filter { it.isNotBlank() }?.distinct() ?: listOf(null)
        transaction {
            for (target in targets) {
                val existingTarget = if (target == null) {
                    ProjectKeys
                        .selectAll()
                        .where {
                            (ProjectKeys.project_id eq projectId) and
                                ProjectKeys.platform_target.isNull()
                        }
                        .firstOrNull()
                } else {
                    ProjectKeys
                        .selectAll()
                        .where {
                            (ProjectKeys.project_id eq projectId) and
                                (ProjectKeys.platform_target eq target)
                        }
                        .firstOrNull()
                }
                check(existingTarget == null) { "Target $target already exists" }
                val publicKey = generatePublicKey()
                val secretKey = generateSecretKey()
                ProjectKeys.insert {
                    it[project_id] = projectId
                    it[public_key] = publicKey
                    it[secret_key] = secretKey
                    it[platform_target] = target
                    it[is_active] = true
                }
            }
        }

        return runBlocking { getProject(projectId) }!!
    }

    fun addProjectTarget(
        projectId: Long,
        target: String
    ): ProjectKeyResponse {
        val existing = transaction {
            ProjectKeys
                .selectAll()
                .where {
                    (ProjectKeys.project_id eq projectId) and
                        (ProjectKeys.platform_target eq target)
                }
                .firstOrNull()
        }
        check(existing == null) { "Target $target already exists" }

        val publicKey = generatePublicKey()
        val secretKey = generateSecretKey()
        transaction {
            ProjectKeys.insert {
                it[project_id] = projectId
                it[public_key] = publicKey
                it[secret_key] = secretKey
                it[platform_target] = target
                it[is_active] = true
            }
        }

        return ProjectKeyResponse(
            platformTarget = target,
            dsn = buildDsn(publicKey, projectId)
        )
    }

    fun updateProject(
        projectId: Long,
        request: UpdateProjectRequest
    ) {
        transaction {
            val row = Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
                ?: return@transaction

            request.name?.let { name ->
                val slug = normalizeSlug(name)
                val conflict = Projects
                    .selectAll()
                    .where {
                        (Projects.organization_id eq row[Projects.organization_id]) and
                            (Projects.id neq projectId) and
                            ((Projects.name eq name) or (Projects.slug eq slug))
                    }
                    .firstOrNull()
                check(conflict == null) { "A project with this name already exists" }
            }

            Projects.update({ Projects.id eq projectId }) {
                request.name?.let { name ->
                    it[Projects.name] = name
                    it[Projects.slug] = normalizeSlug(name)
                }
                request.framework?.let { fw -> it[Projects.framework] = fw }
            }
        }
    }

    fun deleteProject(projectId: Long) {
        transaction {
            ProjectKeys.deleteWhere { ProjectKeys.project_id eq projectId }
            Projects.deleteWhere { Projects.id eq projectId }
        }
    }

    private suspend fun getIssueCountForProject(projectId: Long, demoEpochMs: Long?): Long {
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionClause = queryHelper.timestampRetentionClause("last_seen", retentionDays, demoEpochMs)

        val query = """
            SELECT count() as total
            FROM `$clickhouseDb`.issues FINAL
            WHERE $projectIdClause AND $retentionClause
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) return 0
            if (body.isBlank()) return 0
            val obj = queryHelper.json.parseToJsonElement(body.lines().first()).jsonObject
            obj["total"]?.jsonPrimitive?.long ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue count for project $projectId" }
            0
        }
    }

    private fun buildDsn(publicKey: String, projectId: Long): String {
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        val host = backendUrl.removePrefix("http://").removePrefix("https://")
        val scheme = if (backendUrl.startsWith("https")) "https" else "http"
        return "$scheme://$publicKey@$host/$projectId"
    }

    private fun normalizeSlug(name: String): String {
        return name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "project" }
    }

    private fun generatePublicKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(40)
    }

    private fun generateSecretKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
