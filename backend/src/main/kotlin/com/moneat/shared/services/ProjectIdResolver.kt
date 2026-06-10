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

package com.moneat.shared.services

import com.moneat.shared.models.Projects
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class ProjectIdResolver(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val lookupProjectIdByResourceId: (Uuid) -> Long? = ::lookupProjectIdByResourceId,
    private val lookupResourceIdByProjectId: (Long) -> Uuid? = ::lookupResourceIdByProjectId,
) {
    private data class CachedEntry<T : Any>(val value: T, val expiresAt: Long)

    private val projectIdByResourceId = ConcurrentHashMap<Uuid, CachedEntry<Long>>()
    private val resourceIdByProjectId = ConcurrentHashMap<Long, CachedEntry<Uuid>>()

    fun resolve(param: String): Long? {
        val normalized = param.trim().takeIf { it.isNotBlank() } ?: return null
        val resourceId = parseUuid(normalized) ?: return null
        val now = nowMs()
        projectIdByResourceId[resourceId]?.let { entry ->
            if (entry.expiresAt > now) return entry.value
        }

        val projectId = lookupProjectIdByResourceId(resourceId) ?: return null
        projectIdByResourceId[resourceId] = CachedEntry(projectId, now + CACHE_TTL_MS)
        resourceIdByProjectId[projectId] = CachedEntry(resourceId, now + CACHE_TTL_MS)
        return projectId
    }

    fun resolveProtocolId(param: String): Long? =
        resolve(param) ?: param.trim().toLongOrNull()?.takeIf { it > 0 }

    fun resourceIdFor(projectId: Long): String? {
        val now = nowMs()
        resourceIdByProjectId[projectId]?.let { entry ->
            if (entry.expiresAt > now) return entry.value.toString()
        }

        val resourceId = lookupResourceIdByProjectId(projectId) ?: return null
        resourceIdByProjectId[projectId] = CachedEntry(resourceId, now + CACHE_TTL_MS)
        projectIdByResourceId[resourceId] = CachedEntry(projectId, now + CACHE_TTL_MS)
        return resourceId.toString()
    }

    private fun parseUuid(value: String): Uuid? =
        runCatching { Uuid.parse(value) }.getOrNull()

    companion object {
        private const val MINUTES_PER_HALF_HOUR = 30
        private const val SECONDS_PER_MINUTE = 60
        private const val MILLIS_PER_SECOND = 1_000L
        private const val CACHE_TTL_MS = MINUTES_PER_HALF_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
    }
}

private fun lookupProjectIdByResourceId(resourceId: Uuid): Long? =
    transaction {
        Projects
            .selectAll()
            .where { Projects.resource_id eq resourceId }
            .firstOrNull()
            ?.get(Projects.id)
    }

private fun lookupResourceIdByProjectId(projectId: Long): Uuid? =
    transaction {
        Projects
            .selectAll()
            .where { Projects.id eq projectId }
            .firstOrNull()
            ?.get(Projects.resource_id)
    }
