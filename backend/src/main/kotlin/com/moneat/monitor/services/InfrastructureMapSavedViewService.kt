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

package com.moneat.monitor.services

import com.moneat.monitor.models.INFRASTRUCTURE_MAP_SAVED_VIEW_SCHEMA_VERSION
import com.moneat.monitor.models.InfrastructureMapSavedViewResponse
import com.moneat.monitor.models.InfrastructureMapViewStatePayload
import com.moneat.monitor.models.SaveInfrastructureMapViewRequest
import com.moneat.shared.models.InfrastructureMapSavedViews
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Locale
import kotlin.time.Clock

private const val SAVED_VIEW_NAME_MAX_LENGTH = 48
private const val SAVED_VIEW_SEARCH_QUERY_MAX_LENGTH = 200
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val SAVE_VIEW_MAX_ATTEMPTS = 3

private val savedViewJson = Json {
    ignoreUnknownKeys = true
}

class InvalidInfrastructureMapSavedViewException(message: String) : IllegalArgumentException(message)

data class SaveInfrastructureMapViewResult(
    val view: InfrastructureMapSavedViewResponse,
    val created: Boolean
)

private data class NormalizedSavedViewInput(
    val organizationId: Int,
    val userId: Int,
    val name: String,
    val nameKey: String,
    val resourceKind: String,
    val viewStateJson: String,
    val now: kotlin.time.Instant
)

class InfrastructureMapSavedViewService {
    fun listViews(
        organizationId: Int,
        userId: Int,
    ): List<InfrastructureMapSavedViewResponse> =
        transaction {
            InfrastructureMapSavedViews
                .selectAll()
                .where {
                    (InfrastructureMapSavedViews.organization_id eq organizationId) and
                        (InfrastructureMapSavedViews.user_id eq userId)
                }
                .orderBy(InfrastructureMapSavedViews.updated_at to SortOrder.DESC)
                .map(::rowToResponse)
        }

    fun saveView(
        organizationId: Int,
        userId: Int,
        request: SaveInfrastructureMapViewRequest,
    ): SaveInfrastructureMapViewResult {
        val name = normalizeName(request.name)
        val viewState = normalizeViewState(request)
        val input = NormalizedSavedViewInput(
            organizationId = organizationId,
            userId = userId,
            name = name,
            nameKey = normalizeNameKey(name),
            resourceKind = viewState.resourceKind,
            viewStateJson = savedViewJson.encodeToString(viewState),
            now = Clock.System.now()
        )

        repeat(SAVE_VIEW_MAX_ATTEMPTS) {
            val result = attemptSaveView(input)
            if (result != null) return result
        }
        return updateExistingViewOrThrow(input)
    }

    fun deleteView(
        organizationId: Int,
        userId: Int,
        viewId: Int,
    ): Boolean =
        transaction {
            InfrastructureMapSavedViews.deleteWhere {
                (InfrastructureMapSavedViews.id eq viewId) and
                    (InfrastructureMapSavedViews.organization_id eq organizationId) and
                    (InfrastructureMapSavedViews.user_id eq userId)
            } > 0
        }

    private fun rowToResponse(row: ResultRow): InfrastructureMapSavedViewResponse {
        val viewState = savedViewJson.decodeFromString<InfrastructureMapViewStatePayload>(
            row[InfrastructureMapSavedViews.view_state]
        )
        return InfrastructureMapSavedViewResponse(
            id = row[InfrastructureMapSavedViews.id],
            name = row[InfrastructureMapSavedViews.name],
            resourceKind = viewState.resourceKind,
            groupBy = viewState.groupBy,
            fillBy = viewState.fillBy,
            sizeBy = viewState.sizeBy,
            searchQuery = viewState.searchQuery,
            schemaVersion = viewState.schemaVersion,
            createdAt = row[InfrastructureMapSavedViews.created_at].toString(),
            updatedAt = row[InfrastructureMapSavedViews.updated_at].toString()
        )
    }

    private fun normalizeName(value: String): String {
        val name = value.trim()
        if (name.isBlank()) {
            throw InvalidInfrastructureMapSavedViewException("Saved view name is required")
        }
        if (name.length > SAVED_VIEW_NAME_MAX_LENGTH) {
            throw InvalidInfrastructureMapSavedViewException(
                "Saved view name must be at most $SAVED_VIEW_NAME_MAX_LENGTH characters"
            )
        }
        return name
    }

    private fun normalizeNameKey(name: String): String =
        name.lowercase(Locale.ROOT)

    private fun attemptSaveView(input: NormalizedSavedViewInput): SaveInfrastructureMapViewResult? =
        transaction {
            val existingRow = findViewByNameKey(input)
            if (existingRow != null) {
                return@transaction updateSavedView(existingRow[InfrastructureMapSavedViews.id], input)
            }
            try {
                createSavedView(input)
            } catch (error: ExposedSQLException) {
                if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) null else throw error
            }
        }

    private fun updateExistingViewOrThrow(input: NormalizedSavedViewInput): SaveInfrastructureMapViewResult =
        transaction {
            val row = findViewByNameKey(input)
                ?: error("Saved view upsert race did not resolve for ${input.name}")
            updateSavedView(row[InfrastructureMapSavedViews.id], input)
        }

    private fun findViewByNameKey(input: NormalizedSavedViewInput): ResultRow? =
        InfrastructureMapSavedViews
            .selectAll()
            .where {
                (InfrastructureMapSavedViews.organization_id eq input.organizationId) and
                    (InfrastructureMapSavedViews.user_id eq input.userId) and
                    (InfrastructureMapSavedViews.name_key eq input.nameKey)
            }
            .forUpdate()
            .limit(1)
            .firstOrNull()

    private fun createSavedView(input: NormalizedSavedViewInput): SaveInfrastructureMapViewResult {
        val id = InfrastructureMapSavedViews.insert {
            it[InfrastructureMapSavedViews.organization_id] = input.organizationId
            it[InfrastructureMapSavedViews.user_id] = input.userId
            it[InfrastructureMapSavedViews.name] = input.name
            it[InfrastructureMapSavedViews.name_key] = input.nameKey
            it[InfrastructureMapSavedViews.resource_kind] = input.resourceKind
            it[InfrastructureMapSavedViews.view_state] = input.viewStateJson
            it[InfrastructureMapSavedViews.created_at] = input.now
            it[InfrastructureMapSavedViews.updated_at] = input.now
        }[InfrastructureMapSavedViews.id]
        return savedViewResult(id, created = true)
    }

    private fun updateSavedView(id: Int, input: NormalizedSavedViewInput): SaveInfrastructureMapViewResult {
        InfrastructureMapSavedViews.update({ InfrastructureMapSavedViews.id eq id }) {
            it[InfrastructureMapSavedViews.name] = input.name
            it[InfrastructureMapSavedViews.name_key] = input.nameKey
            it[InfrastructureMapSavedViews.resource_kind] = input.resourceKind
            it[InfrastructureMapSavedViews.view_state] = input.viewStateJson
            it[InfrastructureMapSavedViews.updated_at] = input.now
        }
        return savedViewResult(id, created = false)
    }

    private fun savedViewResult(id: Int, created: Boolean): SaveInfrastructureMapViewResult {
        val row = InfrastructureMapSavedViews
            .selectAll()
            .where { InfrastructureMapSavedViews.id eq id }
            .single()
        return SaveInfrastructureMapViewResult(
            view = rowToResponse(row),
            created = created
        )
    }

    private fun normalizeViewState(request: SaveInfrastructureMapViewRequest): InfrastructureMapViewStatePayload {
        val resourceKind = request.resourceKind.trim()
        val groupBy = request.groupBy.trim()
        val fillBy = request.fillBy.trim()
        val sizeBy = request.sizeBy.trim()
        val searchQuery = request.searchQuery.trim().take(SAVED_VIEW_SEARCH_QUERY_MAX_LENGTH)

        if (resourceKind !in RESOURCE_KINDS) {
            throw InvalidInfrastructureMapSavedViewException("Invalid resource kind")
        }
        if (groupBy !in groupOptions(resourceKind)) {
            throw InvalidInfrastructureMapSavedViewException("Invalid group option")
        }
        if (fillBy !in fillOptions(resourceKind)) {
            throw InvalidInfrastructureMapSavedViewException("Invalid color option")
        }
        if (sizeBy !in sizeOptions(resourceKind)) {
            throw InvalidInfrastructureMapSavedViewException("Invalid size option")
        }

        return InfrastructureMapViewStatePayload(
            resourceKind = resourceKind,
            groupBy = groupBy,
            fillBy = fillBy,
            sizeBy = sizeBy,
            searchQuery = searchQuery,
            schemaVersion = INFRASTRUCTURE_MAP_SAVED_VIEW_SCHEMA_VERSION
        )
    }

    private fun groupOptions(resourceKind: String): Set<String> =
        if (resourceKind == RESOURCE_KIND_CONTAINERS) CONTAINER_GROUP_OPTIONS else HOST_GROUP_OPTIONS

    private fun fillOptions(resourceKind: String): Set<String> =
        if (resourceKind == RESOURCE_KIND_CONTAINERS) CONTAINER_FILL_OPTIONS else HOST_FILL_OPTIONS

    private fun sizeOptions(resourceKind: String): Set<String> =
        if (resourceKind == RESOURCE_KIND_CONTAINERS) CONTAINER_SIZE_OPTIONS else HOST_SIZE_OPTIONS
}

private const val RESOURCE_KIND_HOSTS = "hosts"
private const val RESOURCE_KIND_CONTAINERS = "containers"

private val RESOURCE_KINDS = setOf(RESOURCE_KIND_HOSTS, RESOURCE_KIND_CONTAINERS)
private val HOST_GROUP_OPTIONS = setOf("status", "platform", "os", "agent", "tag:env", "tag:service", "tag:region")
private val CONTAINER_GROUP_OPTIONS = setOf("status", "host", "image", "tag:env", "tag:service", "tag:region")
private val HOST_FILL_OPTIONS = setOf("health", "memory", "cpu", "lastSeen")
private val CONTAINER_FILL_OPTIONS = setOf("health", "cpu", "memory", "network", "lastSeen")
private val HOST_SIZE_OPTIONS = setOf("uniform", "memory", "cpu")
private val CONTAINER_SIZE_OPTIONS = setOf("uniform", "cpu", "memory", "network")
