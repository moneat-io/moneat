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

package com.moneat.datadog.services

import com.moneat.datadog.models.CreateDdApiKeyResponse
import com.moneat.datadog.models.DdApiKeyResponse
import com.moneat.datadog.models.DdApiKeys
import com.moneat.shared.models.Projects
import com.moneat.shared.services.toUuidOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import kotlin.uuid.Uuid

object DatadogService {
    private const val KEY_PREFIX = "magt_"
    private const val KEY_LENGTH = 32 // 32 bytes = 64 hex chars
    private const val PREFIX_LENGTH = 12 // First 12 chars for display

    fun listApiKeys(organizationId: Int): List<DdApiKeyResponse> =
        transaction {
            DdApiKeys
                .selectAll()
                .where { DdApiKeys.organizationId eq organizationId }
                .map { it.toDdApiKeyResponse() }
        }

    fun createApiKey(
        organizationId: Int,
        name: String,
        userId: Int,
        projectId: String? = null,
    ): CreateDdApiKeyResponse {
        val internalProjectId = projectId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                resolveProjectId(organizationId, it)
                    ?: throw IllegalArgumentException("Project not found")
            }
        return createApiKeyForProject(
            organizationId = organizationId,
            name = name,
            userId = userId,
            projectId = internalProjectId,
        )
    }

    fun createApiKeyForProjectId(
        organizationId: Int,
        name: String,
        userId: Int,
        projectId: Int? = null,
    ): CreateDdApiKeyResponse =
        createApiKeyForProject(
            organizationId = organizationId,
            name = name,
            userId = userId,
            projectId = projectId,
        )

    private fun createApiKeyForProject(
        organizationId: Int,
        name: String,
        userId: Int,
        projectId: Int? = null,
    ): CreateDdApiKeyResponse =
        transaction {
            if (projectId != null && !projectBelongsToOrg(projectId, organizationId)) {
                throw IllegalArgumentException("Project not found")
            }
            val key = generateApiKey()
            val keyHash = hashKey(key)
            val keyPrefix = key.take(PREFIX_LENGTH)
            val now = Instant.now()

            val row = DdApiKeys.insert {
                it[DdApiKeys.organizationId] = organizationId
                it[DdApiKeys.projectId] = projectId
                it[DdApiKeys.name] = name
                it[DdApiKeys.keyHash] = keyHash
                it[DdApiKeys.keyPrefix] = keyPrefix
                it[DdApiKeys.createdBy] = userId
                it[DdApiKeys.createdAt] = now
                it[DdApiKeys.isActive] = true
            }

            CreateDdApiKeyResponse(
                id = row[DdApiKeys.resourceId].toString(),
                name = name,
                key = key,
                keyPrefix = keyPrefix,
            )
        }

    private fun resolveProjectId(organizationId: Int, projectResourceId: String): Int? {
        val resourceId = projectResourceId.toUuidOrNull() ?: return null
        return transaction {
            Projects
                .selectAll()
                .where {
                    (Projects.resource_id eq resourceId) and
                        (Projects.organization_id eq organizationId)
                }
                .firstOrNull()
                ?.get(Projects.id)
                ?.toInt()
        }
    }

    private fun projectBelongsToOrg(projectId: Int, organizationId: Int): Boolean =
        Projects
            .selectAll()
            .where {
                (Projects.id eq projectId.toLong()) and
                    (Projects.organization_id eq organizationId)
            }
            .limit(1)
            .count() > 0

    fun revokeApiKey(
        keyId: Uuid,
        organizationId: Int,
    ): Boolean =
        transaction {
            DdApiKeys.update({
                (DdApiKeys.resourceId eq keyId) and (DdApiKeys.organizationId eq organizationId)
            }) {
                it[isActive] = false
            } > 0
        }

    fun revokeApiKey(
        keyId: String,
        organizationId: Int,
    ): Boolean =
        parseResourceId(keyId)?.let { parsedId -> revokeApiKey(parsedId, organizationId) } ?: false

    fun deleteApiKey(
        keyId: Uuid,
        organizationId: Int,
    ): Boolean =
        transaction {
            DdApiKeys.deleteWhere { (resourceId eq keyId) and (DdApiKeys.organizationId eq organizationId) } > 0
        }

    fun deleteApiKey(
        keyId: String,
        organizationId: Int,
    ): Boolean =
        parseResourceId(keyId)?.let { parsedId -> deleteApiKey(parsedId, organizationId) } ?: false

    data class ApiKeyValidation(
        val organizationId: Int,
        val projectId: Int?,
    )

    fun validateApiKey(key: String): Int? =
        validateApiKeyContext(key)?.organizationId

    fun validateApiKeyContext(key: String): ApiKeyValidation? {
        val keyHash = hashKey(key)
        return transaction {
            val row = DdApiKeys
                .selectAll()
                .where { (DdApiKeys.keyHash eq keyHash) and (DdApiKeys.isActive eq true) }
                .singleOrNull()
                ?: return@transaction null

            DdApiKeys.update({ DdApiKeys.keyHash eq keyHash }) {
                it[lastUsedAt] = Instant.now()
            }

            ApiKeyValidation(
                organizationId = row[DdApiKeys.organizationId],
                projectId = row[DdApiKeys.projectId],
            )
        }
    }

    private fun generateApiKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(KEY_LENGTH)
        random.nextBytes(bytes)
        return KEY_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun parseResourceId(value: String): Uuid? =
        value.toUuidOrNull()

    private fun org.jetbrains.exposed.v1.core.ResultRow.toDdApiKeyResponse() =
        DdApiKeyResponse(
            id = this[DdApiKeys.resourceId].toString(),
            name = this[DdApiKeys.name],
            keyPrefix = this[DdApiKeys.keyPrefix],
            createdAt = this[DdApiKeys.createdAt].toString(),
            lastUsedAt = this[DdApiKeys.lastUsedAt]?.toString(),
            isActive = this[DdApiKeys.isActive],
        )
}
