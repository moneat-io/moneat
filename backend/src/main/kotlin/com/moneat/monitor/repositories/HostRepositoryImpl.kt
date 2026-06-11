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

package com.moneat.monitor.repositories

import com.moneat.config.ClickHouseClient
import com.moneat.monitor.models.HostData
import com.moneat.shared.models.Hosts
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private val hostTagsJson = Json { ignoreUnknownKeys = true }

class HostRepositoryImpl : HostRepository {

    override fun getHostCountForOrganization(organizationId: Int): Int =
        transaction {
            Hosts.selectAll().where { Hosts.organization_id eq organizationId }.count().toInt()
        }

    override fun listByOrganizationId(organizationId: Int): List<HostData> =
        transaction {
            Hosts
                .selectAll()
                .where { Hosts.organization_id eq organizationId }
                .orderBy(Hosts.first_seen_at to SortOrder.DESC)
                .map { rowToHostData(it) }
        }

    override fun getById(hostId: Int): HostData? =
        transaction {
            Hosts
                .selectAll()
                .where { Hosts.id eq hostId }
                .firstOrNull()
                ?.let { rowToHostData(it) }
        }

    override fun getByResourceId(
        resourceId: Uuid,
        organizationIds: List<Int>
    ): HostData? {
        if (organizationIds.isEmpty()) return null
        return transaction {
            Hosts
                .selectAll()
                .where {
                    (Hosts.resource_id eq resourceId) and
                        (Hosts.organization_id inList organizationIds)
                }
                .firstOrNull()
                ?.let { rowToHostData(it) }
        }
    }

    override fun delete(hostId: Int, organizationId: Int): Boolean =
        transaction {
            val deleted = Hosts.deleteWhere {
                (Hosts.id eq hostId) and (Hosts.organization_id eq organizationId)
            }
            deleted > 0
        }

    override suspend fun executeClickHouseQuery(sql: String): String {
        val response = ClickHouseClient.execute(sql)
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            logger.error { "ClickHouse query failed: $body" }
            return ""
        }
        return response.bodyAsText()
    }

    override suspend fun deleteClickHouseData(sql: String): Boolean {
        val response = ClickHouseClient.execute(sql)
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            logger.error { "ClickHouse delete failed: $body" }
            return false
        }
        return true
    }

    private fun rowToHostData(row: ResultRow): HostData =
        HostData(
            id = row[Hosts.id],
            resourceId = row[Hosts.resource_id],
            organizationId = row[Hosts.organization_id],
            hostname = row[Hosts.hostname],
            displayName = row[Hosts.display_name],
            status = row[Hosts.status],
            lastSeenAt = row[Hosts.last_seen_at],
            agentVersion = row[Hosts.agent_version].takeIf { it.isNotBlank() },
            os = row[Hosts.os].takeIf { it.isNotBlank() },
            arch = row[Hosts.arch],
            platform = row[Hosts.platform].takeIf { it.isNotBlank() },
            processor = row[Hosts.processor].takeIf { it.isNotBlank() },
            cpuCores = row[Hosts.cpu_cores].takeIf { it > 0 },
            memoryTotalKb = row[Hosts.memory_total_kb].takeIf { it > 0 },
            tags = parseHostTags(row[Hosts.tags]),
            firstSeenAt = row[Hosts.first_seen_at],
            createdAt = row[Hosts.first_seen_at]
        )
}

private fun parseHostTags(rawTags: String): Map<String, String> {
    if (rawTags.isBlank()) return emptyMap()
    return runCatching {
        hostTagsJson
            .parseToJsonElement(rawTags)
            .jsonObject
            .entries
            .mapNotNull { (key, value) ->
                (value as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { key to it }
            }
            .toMap()
    }.getOrDefault(emptyMap())
}
