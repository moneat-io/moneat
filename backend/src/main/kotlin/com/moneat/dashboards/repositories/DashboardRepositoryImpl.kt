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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
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

    private fun ResultRow.toDashboardWithFavoriteFlag(isFavorited: Boolean): DashboardWithFavoriteFlag =
        DashboardWithFavoriteFlag(
            id = this[Dashboards.id],
            resourceId = this[Dashboards.resourceId].toString(),
            orgId = this[Dashboards.orgId],
            projectId = this[Dashboards.projectId],
            projectResourceId = projectResourceId(this[Dashboards.projectId]),
            folderId = this[Dashboards.folderId],
            folderResourceId = folderResourceId(this[Dashboards.folderId]),
            title = this[Dashboards.title],
            description = this[Dashboards.description],
            layoutType = this[Dashboards.layoutType],
            isDefault = this[Dashboards.isDefault],
            isFavorited = isFavorited,
            variables = this[Dashboards.variables],
            createdBy = this[Dashboards.createdBy],
            createdAt = this[Dashboards.createdAt].toString(),
            updatedAt = this[Dashboards.updatedAt].toString()
        )

    private fun projectResourceId(projectId: Long?): String? =
        projectId?.let { id ->
            Projects.selectAll()
                .where { Projects.id eq id }
                .firstOrNull()
                ?.get(Projects.resource_id)
                ?.toString()
        }

    private fun folderResourceId(folderId: Long?): String? =
        folderId?.let { id ->
            DashboardFolders.selectAll()
                .where { DashboardFolders.id eq id }
                .firstOrNull()
                ?.get(DashboardFolders.resourceId)
                ?.toString()
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

            query.map { row -> row.toDashboardWithFavoriteFlag(row[Dashboards.id] in favoritedIds) }
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

            row.toDashboardWithFavoriteFlag(isFavorited)
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
                projectId = projectId,
                projectResourceId = projectResourceId(projectId),
                folderId = folderId,
                folderResourceId = folderResourceId(folderId),
                title = request.title,
                description = request.description,
                layoutType = request.layoutType,
                isDefault = request.isDefault,
                variables = json.encodeToString<List<DashboardVariable>>(request.variables),
                createdBy = userId,
                createdAt = now.toString(),
                updatedAt = now.toString()
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
            Dashboards.selectAll()
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
                .map { row ->
                    val did = row[Dashboards.id]
                    val isFav = userId?.let { uid ->
                        DashboardFavorites.selectAll()
                            .where {
                                (DashboardFavorites.userId eq uid) and
                                    (DashboardFavorites.dashboardId eq did)
                            }
                            .any()
                    } ?: false
                    row.toDashboardWithFavoriteFlag(isFav)
                }
        }
}
