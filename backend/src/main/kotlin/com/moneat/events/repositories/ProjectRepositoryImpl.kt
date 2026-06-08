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

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.events.models.ProjectKeyResponse
import com.moneat.events.models.UpdateProjectRequest
import com.moneat.events.repositories.models.ProjectRow
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.shared.services.ServiceIdentityResolver
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private val logger = KotlinLogging.logger {}

class ProjectRepositoryImpl(
    private val serviceIdentityResolver: ServiceIdentityResolver = ServiceIdentityResolver(),
    private val timestampRetentionClause: (String, Int, Long?) -> String
) : ProjectRepository {

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun getOrganizationIdsForUser(userId: Int): List<Int> =
        transaction {
            Memberships
                .selectAll()
                .where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }
                .distinct()
        }

    override fun getProjectsForOrganizations(orgIds: List<Int>): List<ProjectRow> =
        transaction {
            val rows = Projects
                .selectAll()
                .where { Projects.organization_id inList orgIds }
                .toList()
            val projectIds = rows.map { it[Projects.id] }
            val keysByProject = getProjectKeysBatch(projectIds)
            rows.map { row -> buildProjectRow(row, keysByProject[row[Projects.id]] ?: emptyList()) }
        }

    override fun getProjectById(projectId: Long): ProjectRow? =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.let { row -> buildProjectRow(row, getProjectKeys(projectId)) }
        }

    override fun getServiceNameForProject(projectId: Long): String? =
        serviceIdentityResolver.serviceNameForProject(projectId)

    override fun resolveServiceId(orgId: Int, serviceName: String, serviceNamespace: String): Long? =
        serviceIdentityResolver.resolveServiceId(orgId, serviceName, serviceNamespace)

    override fun resolveServiceNames(projectIds: List<Long>): Map<Long, String> =
        serviceIdentityResolver.serviceNamesForProjects(projectIds)

    override fun getProjectCountForOrganization(orgId: Int): Int =
        transaction {
            Projects.selectAll().where { Projects.organization_id eq orgId }.count().toInt()
        }

    override fun findProjectByNameOrSlug(orgId: Int, name: String, slug: String): ProjectRow? =
        transaction {
            Projects
                .selectAll()
                .where {
                    (Projects.organization_id eq orgId) and
                        ((Projects.name eq name) or (Projects.slug eq slug))
                }
                .firstOrNull()
                ?.let { row -> buildProjectRow(row, getProjectKeys(row[Projects.id])) }
        }

    override fun createProject(orgId: Int, name: String, slug: String, framework: String?): Long =
        transaction {
            Projects.insert {
                it[Projects.organization_id] = orgId
                it[Projects.name] = name
                it[Projects.slug] = slug
                it[Projects.framework] = framework
            }[Projects.id]
        }

    override fun createProjectKey(
        projectId: Long,
        publicKey: String,
        secretKey: String,
        platformTarget: String?
    ) {
        transaction {
            ProjectKeys.insert {
                it[ProjectKeys.project_id] = projectId
                it[ProjectKeys.public_key] = publicKey
                it[ProjectKeys.secret_key] = secretKey
                it[ProjectKeys.platform_target] = platformTarget
                it[ProjectKeys.is_active] = true
            }
        }
    }

    override fun findProjectKeyByTarget(projectId: Long, target: String?): Boolean =
        transaction {
            val query = if (target == null) {
                ProjectKeys
                    .selectAll()
                    .where {
                        (ProjectKeys.project_id eq projectId) and
                            ProjectKeys.platform_target.isNull() and
                            (ProjectKeys.is_active eq true)
                    }
            } else {
                ProjectKeys
                    .selectAll()
                    .where {
                        (ProjectKeys.project_id eq projectId) and
                            (ProjectKeys.platform_target eq target) and
                            (ProjectKeys.is_active eq true)
                    }
            }
            query.firstOrNull() != null
        }

    override fun updateProject(projectId: Long, request: UpdateProjectRequest) {
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

    override fun deleteProject(projectId: Long) {
        transaction {
            ProjectKeys.deleteWhere { ProjectKeys.project_id eq projectId }
            Projects.deleteWhere { Projects.id eq projectId }
        }
    }

    override fun searchProjectsByName(orgId: Int, namePattern: String, limit: Int): List<ProjectRow> =
        transaction {
            val pattern = namePattern.lowercase()
            Projects
                .selectAll()
                .where { (Projects.organization_id eq orgId) and (Projects.name.lowerCase() like pattern) }
                .limit(limit)
                .map { row ->
                    ProjectRow(
                        projectId = row[Projects.id],
                        resourceId = row[Projects.resource_id].toString(),
                        name = row[Projects.name],
                        slug = row[Projects.slug],
                        framework = row[Projects.framework],
                        keys = emptyList(),
                        dsn = "",
                        serviceId = row[Projects.id],
                        serviceName = projectServiceName(row),
                    )
                }
        }

    override suspend fun getIssueCountForProject(
        projectId: Long,
        retentionDays: Int,
        demoEpochMs: Long?
    ): Long {
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionClause = timestampRetentionClause("last_seen", retentionDays, demoEpochMs)

        val query = """
            SELECT count() as total
            FROM `$clickhouseDb`.issues FINAL
            WHERE $projectIdClause AND $retentionClause
            FORMAT JSONEachRow
        """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX ||
                body.trimStart().startsWith("Code:")
            ) {
                return 0
            }
            if (body.isBlank()) return 0
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["total"]?.jsonPrimitive?.long ?: 0
        }.getOrElse { e ->
            logger.error(e) { "Failed to get issue count for project $projectId" }
            0
        }
    }

    private fun getProjectKeys(projectId: Long): List<ProjectKeyResponse> =
        ProjectKeys
            .selectAll()
            .where { (ProjectKeys.project_id eq projectId) and (ProjectKeys.is_active eq true) }
            .map { k ->
                val pubKey = k[ProjectKeys.public_key]
                ProjectKeyResponse(
                    platformTarget = k[ProjectKeys.platform_target],
                    dsn = buildDsn(pubKey, projectId)
                )
            }

    private fun getProjectKeysBatch(projectIds: List<Long>): Map<Long, List<ProjectKeyResponse>> {
        if (projectIds.isEmpty()) return emptyMap()
        return ProjectKeys
            .selectAll()
            .where { (ProjectKeys.project_id inList projectIds) and (ProjectKeys.is_active eq true) }
            .groupBy({ it[ProjectKeys.project_id] }) { k ->
                ProjectKeyResponse(
                    platformTarget = k[ProjectKeys.platform_target],
                    dsn = buildDsn(k[ProjectKeys.public_key], k[ProjectKeys.project_id])
                )
            }
    }

    private fun buildProjectRow(row: ResultRow, keys: List<ProjectKeyResponse>): ProjectRow {
        val firstDsn = keys.firstOrNull { it.platformTarget == null }?.dsn ?: keys.firstOrNull()?.dsn ?: ""
        return ProjectRow(
            projectId = row[Projects.id],
            resourceId = row[Projects.resource_id].toString(),
            name = row[Projects.name],
            slug = row[Projects.slug],
            framework = row[Projects.framework],
            keys = keys,
            dsn = firstDsn,
            serviceId = row[Projects.id],
            serviceName = projectServiceName(row),
        )
    }

    private fun projectServiceName(row: ResultRow): String =
        row[Projects.slug]
            .trim()
            .ifBlank { row[Projects.name].trim() }
            .ifBlank { row[Projects.id].toString() }

    private fun buildDsn(publicKey: String, projectId: Long): String {
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        val host = backendUrl.removePrefix("http://").removePrefix("https://")
        val scheme = if (backendUrl.startsWith("https")) "https" else "http"
        return "$scheme://$publicKey@$host/$projectId"
    }

    private fun normalizeSlug(name: String): String =
        name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "project" }
}
