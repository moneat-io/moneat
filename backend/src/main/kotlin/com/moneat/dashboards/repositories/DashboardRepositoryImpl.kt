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
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.UpdateDashboardRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

            query.map { row ->
                DashboardWithFavoriteFlag(
                    id = row[Dashboards.id],
                    orgId = row[Dashboards.orgId],
                    projectId = row[Dashboards.projectId],
                    folderId = row[Dashboards.folderId],
                    title = row[Dashboards.title],
                    description = row[Dashboards.description],
                    layoutType = row[Dashboards.layoutType],
                    isDefault = row[Dashboards.isDefault],
                    isFavorited = row[Dashboards.id] in favoritedIds,
                    variables = row[Dashboards.variables],
                    createdBy = row[Dashboards.createdBy],
                    createdAt = row[Dashboards.createdAt].toString(),
                    updatedAt = row[Dashboards.updatedAt].toString()
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

            DashboardWithFavoriteFlag(
                id = row[Dashboards.id],
                orgId = row[Dashboards.orgId],
                projectId = row[Dashboards.projectId],
                folderId = row[Dashboards.folderId],
                title = row[Dashboards.title],
                description = row[Dashboards.description],
                layoutType = row[Dashboards.layoutType],
                isDefault = row[Dashboards.isDefault],
                isFavorited = isFavorited,
                variables = row[Dashboards.variables],
                createdBy = row[Dashboards.createdBy],
                createdAt = row[Dashboards.createdAt].toString(),
                updatedAt = row[Dashboards.updatedAt].toString()
            )
        }

    override fun create(orgId: Long, userId: Long, request: CreateDashboardRequest): CreatedDashboardData =
        transaction {
            val now = Clock.System.now()
            val dashboardId = Dashboards.insert {
                it[Dashboards.orgId] = orgId
                it[Dashboards.projectId] = request.projectId
                it[Dashboards.folderId] = request.folderId
                it[Dashboards.title] = request.title
                it[Dashboards.description] = request.description
                it[Dashboards.layoutType] = request.layoutType
                it[Dashboards.isDefault] = request.isDefault
                it[Dashboards.variables] = json.encodeToString<List<DashboardVariable>>(request.variables)
                it[Dashboards.createdBy] = userId
                it[Dashboards.createdAt] = now
                it[Dashboards.updatedAt] = now
            } get Dashboards.id

            CreatedDashboardData(
                id = dashboardId,
                orgId = orgId,
                projectId = request.projectId,
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

    override fun update(id: Long, orgId: Long, request: UpdateDashboardRequest): Boolean =
        transaction {
            val exists = Dashboards.selectAll().where {
                (Dashboards.id eq id) and (Dashboards.orgId eq orgId)
            }.any()
            if (!exists) return@transaction false

            val now = Clock.System.now()
            Dashboards.update({ (Dashboards.id eq id) and (Dashboards.orgId eq orgId) }) {
                request.title?.let { t -> it[Dashboards.title] = t }
                request.description?.let { d -> it[Dashboards.description] = d }
                request.folderId?.let { fid -> it[Dashboards.folderId] = fid }
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
                    DashboardWithFavoriteFlag(
                        id = did,
                        orgId = row[Dashboards.orgId],
                        projectId = row[Dashboards.projectId],
                        folderId = row[Dashboards.folderId],
                        title = row[Dashboards.title],
                        description = row[Dashboards.description],
                        layoutType = row[Dashboards.layoutType],
                        isDefault = row[Dashboards.isDefault],
                        isFavorited = isFav,
                        variables = row[Dashboards.variables],
                        createdBy = row[Dashboards.createdBy],
                        createdAt = row[Dashboards.createdAt].toString(),
                        updatedAt = row[Dashboards.updatedAt].toString()
                    )
                }
        }
}
