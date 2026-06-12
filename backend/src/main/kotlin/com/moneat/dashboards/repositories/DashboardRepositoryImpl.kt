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

package com.moneat.dashboards.repositories

import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.DashboardFavorites
import com.moneat.dashboards.models.DashboardFolders
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.shared.services.organizationResourceId
import com.moneat.shared.services.organizationResourceIds
import com.moneat.shared.services.userResourceId
import com.moneat.shared.services.userResourceIds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class DashboardRepositoryImpl : DashboardRepository {
    companion object {
        private const val RECENTLY_VIEWED_LIMIT = 10
    }

    private data class DashboardResourceIds(
        val organizations: Map<Long, String>,
        val users: Map<Long, String>,
        val projects: Map<Long, String>,
        val folders: Map<Long, String>,
    ) {
        fun organization(id: Long): String =
            organizations[id] ?: error("Missing resource_id for organization $id")

        fun user(id: Long): String =
            users[id] ?: error("Missing resource_id for user $id")

        fun project(id: Long?): String? =
            id?.let(projects::get)

        fun folder(id: Long?): String? =
            id?.let(folders::get)
    }

    private fun ResultRow.toDashboardWithFavoriteFlag(
        isFavorited: Boolean,
        resourceIds: DashboardResourceIds,
        ownerName: String? = null,
    ): DashboardWithFavoriteFlag =
        DashboardWithFavoriteFlag(
            id = this[Dashboards.id],
            resourceId = this[Dashboards.resourceId].toString(),
            orgId = this[Dashboards.orgId],
            orgResourceId = resourceIds.organization(this[Dashboards.orgId]),
            projectId = this[Dashboards.projectId],
            projectResourceId = resourceIds.project(this[Dashboards.projectId]),
            folderId = this[Dashboards.folderId],
            folderResourceId = resourceIds.folder(this[Dashboards.folderId]),
            title = this[Dashboards.title],
            description = this[Dashboards.description],
            layoutType = this[Dashboards.layoutType],
            isDefault = this[Dashboards.isDefault],
            isFavorited = isFavorited,
            variables = this[Dashboards.variables],
            createdBy = this[Dashboards.createdBy],
            createdByResourceId = resourceIds.user(this[Dashboards.createdBy]),
            createdAt = this[Dashboards.createdAt].toString(),
            updatedAt = this[Dashboards.updatedAt].toString(),
            ownerName = ownerName
        )

    private fun resourceIdsForRows(rows: List<ResultRow>): DashboardResourceIds {
        val projectIds = rows.mapNotNull { row -> row[Dashboards.projectId] }.distinct()
        val folderIds = rows.mapNotNull { row -> row[Dashboards.folderId] }.distinct()
        return DashboardResourceIds(
            organizations = organizationResourceIds(rows.map { row -> row[Dashboards.orgId].toInt() })
                .mapKeys { (id, _) -> id.toLong() },
            users = userResourceIds(rows.map { row -> row[Dashboards.createdBy].toInt() })
                .mapKeys { (id, _) -> id.toLong() },
            projects = projectResourceIds(projectIds),
            folders = folderResourceIds(folderIds),
        )
    }

    private fun projectResourceIds(projectIds: List<Long>): Map<Long, String> {
        if (projectIds.isEmpty()) return emptyMap()
        return Projects
            .selectAll()
            .where { Projects.id inList projectIds }
            .associate { row -> row[Projects.id] to row[Projects.resource_id].toString() }
    }

    private fun folderResourceIds(folderIds: List<Long>): Map<Long, String> {
        if (folderIds.isEmpty()) return emptyMap()
        return DashboardFolders
            .selectAll()
            .where { DashboardFolders.id inList folderIds }
            .associate { row -> row[DashboardFolders.id] to row[DashboardFolders.resourceId].toString() }
    }

    private fun loadOwnerNames(createdByIds: Iterable<Long>): Map<Long, String?> {
        val ownerIds = createdByIds.distinct()
            .mapNotNull { createdBy ->
                val intId = createdBy.toInt()
                if (intId.toLong() == createdBy) createdBy to intId else null
            }
        if (ownerIds.isEmpty()) return emptyMap()
        val userIds = ownerIds.map { it.second }
        val namesByUserId = Users.select(Users.id, Users.name)
            .where { Users.id inList userIds }
            .associate { row -> row[Users.id] to row[Users.name] }
        return ownerIds.associate { (createdBy, userId) -> createdBy to namesByUserId[userId] }
    }

    override fun list(orgId: Long, projectId: Long?, userId: Int?): List<DashboardWithFavoriteFlag> =
        transaction {
            val query = Dashboards.selectAll().where {
                if (projectId != null) {
                    (Dashboards.orgId eq orgId) and (Dashboards.projectId eq projectId)
                } else {
                    Dashboards.orgId eq orgId
                }
            }.orderBy(Dashboards.updatedAt, SortOrder.DESC)

            val favoritedIds = userId?.let { uid ->
                DashboardFavorites.selectAll()
                    .where { DashboardFavorites.userId eq uid }
                    .map { it[DashboardFavorites.dashboardId] }
                    .toSet()
            } ?: emptySet()

            val rows = query.toList()
            val ownerNames = loadOwnerNames(rows.map { row -> row[Dashboards.createdBy] })
            val resourceIds = resourceIdsForRows(rows)

            rows.map { row ->
                val createdBy = row[Dashboards.createdBy]
                row.toDashboardWithFavoriteFlag(
                    isFavorited = row[Dashboards.id] in favoritedIds,
                    resourceIds = resourceIds,
                    ownerName = ownerNames[createdBy]
                )
            }
        }

    override fun getById(id: Long, orgId: Long, userId: Int?): DashboardWithFavoriteFlag? =
        transaction {
            val row = Dashboards.selectAll().where {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }.firstOrNull() ?: return@transaction null

            val isFavorited = userId?.let { uid ->
                DashboardFavorites.selectAll()
                    .where {
                        (DashboardFavorites.userId eq uid) and (DashboardFavorites.dashboardId eq id)
                    }
                    .any()
            } ?: false

            val createdBy = row[Dashboards.createdBy]
            row.toDashboardWithFavoriteFlag(
                isFavorited = isFavorited,
                resourceIds = resourceIdsForRows(listOf(row)),
                ownerName = loadOwnerNames(listOf(createdBy))[createdBy]
            )
        }

    override fun create(
        orgId: Long,
        userId: Long,
        request: CreateDashboardRequest,
        projectId: Long?,
        folderId: Long?
    ): CreatedDashboardData =
        transaction {
            val now = Clock.System.now()
            val ownerName = loadOwnerNames(listOf(userId))[userId]
            val dashboardId = Dashboards.insert {
                it[Dashboards.orgId] = orgId
                it[Dashboards.projectId] = projectId
                it[Dashboards.folderId] = folderId
                it[Dashboards.title] = request.title
                it[Dashboards.description] = request.description
                it[Dashboards.layoutType] = request.layoutType
                it[Dashboards.isDefault] = request.isDefault
                it[Dashboards.variables] = json.encodeToString<List<DashboardVariable>>(request.variables)
                it[Dashboards.createdBy] = userId
                it[Dashboards.createdAt] = now
                it[Dashboards.updatedAt] = now
            } get Dashboards.id

            val dashboard = Dashboards.selectAll()
                .where { Dashboards.id eq dashboardId }
                .first()

            CreatedDashboardData(
                id = dashboardId,
                resourceId = dashboard[Dashboards.resourceId].toString(),
                orgId = orgId,
                orgResourceId = organizationResourceId(orgId),
                projectId = projectId,
                projectResourceId = projectResourceIds(listOfNotNull(projectId))[projectId],
                folderId = folderId,
                folderResourceId = folderResourceIds(listOfNotNull(folderId))[folderId],
                title = request.title,
                description = request.description,
                layoutType = request.layoutType,
                isDefault = request.isDefault,
                variables = json.encodeToString<List<DashboardVariable>>(request.variables),
                createdBy = userId,
                createdByResourceId = userResourceId(userId),
                createdAt = now.toString(),
                updatedAt = now.toString(),
                ownerName = ownerName
            )
        }

    override fun update(id: Long, orgId: Long, request: UpdateDashboardRequest, folderId: Long?): Boolean =
        transaction {
            val exists = Dashboards.selectAll().where {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }.any()
            if (!exists) return@transaction false

            val now = Clock.System.now()
            Dashboards.update({ (Dashboards.id eq id) and (Dashboards.orgId eq orgId) }) {
                request.title?.let { t -> it[Dashboards.title] = t }
                request.description?.let { d -> it[Dashboards.description] = d }
                if (request.folderId != null) it[Dashboards.folderId] = folderId
                request.layoutType?.let { lt -> it[Dashboards.layoutType] = lt }
                request.isDefault?.let { d -> it[Dashboards.isDefault] = d }
                request.variables?.let { v ->
                    it[Dashboards.variables] = json.encodeToString<List<DashboardVariable>>(v)
                }
                it[Dashboards.updatedAt] = now
            }
            true
        }

    override fun moveToFolder(id: Long, orgId: Long, folderId: Long?): Boolean =
        transaction {
            val updated = Dashboards.update({ (Dashboards.id eq id) and (Dashboards.orgId eq orgId) }) {
                it[Dashboards.folderId] = folderId
                it[Dashboards.updatedAt] = Clock.System.now()
            }
            updated > 0
        }

    override fun setDefault(id: Long, orgId: Long): Boolean =
        transaction {
            val exists = Dashboards.selectAll().where {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }.any()
            if (!exists) return@transaction false

            val now = Clock.System.now()
            // Only one dashboard per org is the default — clear the others first.
            Dashboards.update({ (Dashboards.orgId eq orgId) and (Dashboards.isDefault eq true) }) {
                it[Dashboards.isDefault] = false
                it[Dashboards.updatedAt] = now
            }
            Dashboards.update({ (Dashboards.id eq id) and (Dashboards.orgId eq orgId) }) {
                it[Dashboards.isDefault] = true
                it[Dashboards.updatedAt] = now
            }
            true
        }

    override fun delete(id: Long, orgId: Long): Boolean =
        transaction {
            val deleted = Dashboards.deleteWhere {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }
            deleted > 0
        }

    override fun toggleFavorite(userId: Int, dashboardId: Long, orgId: Long): Boolean =
        transaction {
            val exists = Dashboards.selectAll().where {
                (Dashboards.id eq dashboardId) and (Dashboards.orgId eq orgId)
            }.any()
            if (!exists) return@transaction false

            val alreadyFavorited = DashboardFavorites.selectAll().where {
                (DashboardFavorites.userId eq userId) and (DashboardFavorites.dashboardId eq dashboardId)
            }.any()

            if (alreadyFavorited) {
                DashboardFavorites.deleteWhere {
                    (DashboardFavorites.userId eq userId) and (DashboardFavorites.dashboardId eq dashboardId)
                }
                false
            } else {
                DashboardFavorites.insert {
                    it[DashboardFavorites.userId] = userId
                    it[DashboardFavorites.dashboardId] = dashboardId
                    it[DashboardFavorites.createdAt] = Clock.System.now()
                }
                true
            }
        }

    override fun search(orgId: Long, userId: Int?, pattern: String): List<DashboardWithFavoriteFlag> =
        transaction {
            val rows = Dashboards.selectAll()
                .where {
                    (Dashboards.orgId eq orgId) and (
                        (Dashboards.title.lowerCase() like pattern) or
                            (
                                (Dashboards.description.isNotNull()) and
                                    (Dashboards.description.lowerCase() like pattern)
                                )
                        )
                }
                .orderBy(Dashboards.updatedAt, SortOrder.DESC)
                .limit(RECENTLY_VIEWED_LIMIT)
                .toList()
            val dashboardIds = rows.map { row -> row[Dashboards.id] }
            val favoritedIds = userId?.let { uid ->
                if (dashboardIds.isEmpty()) {
                    emptySet()
                } else {
                    DashboardFavorites
                        .selectAll()
                        .where {
                            (DashboardFavorites.userId eq uid) and
                                (DashboardFavorites.dashboardId inList dashboardIds)
                        }
                        .map { row -> row[DashboardFavorites.dashboardId] }
                        .toSet()
                }
            } ?: emptySet()
            val ownerNames = loadOwnerNames(rows.map { row -> row[Dashboards.createdBy] })
            val resourceIds = resourceIdsForRows(rows)
            rows.map { row ->
                val createdBy = row[Dashboards.createdBy]
                row.toDashboardWithFavoriteFlag(
                    isFavorited = row[Dashboards.id] in favoritedIds,
                    resourceIds = resourceIds,
                    ownerName = ownerNames[createdBy]
                )
            }
        }
}
