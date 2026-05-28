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

package com.moneat.mcp.services

import com.moneat.mcp.models.CreateMcpApiKeyResponse
import com.moneat.mcp.models.McpApiKeyResponse
import com.moneat.mcp.models.McpApiKeyValidationResult
import com.moneat.shared.models.McpApiKeys
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.time.Clock

private const val KEY_PREFIX = "mmcp_"
private const val KEY_RANDOM_BYTES = 32
private const val DISPLAY_PREFIX_LENGTH = 12
private const val SECONDS_PER_DAY = 86_400
private const val KEY_NAME_MAX_LENGTH = 255

class McpApiKeyService {

    fun createKey(
        organizationId: Int,
        userId: Int,
        name: String,
        enabledTools: List<String>,
        enabledResources: List<String>,
        expiresInDays: Int? = null,
    ): CreateMcpApiKeyResponse {
        validateName(name)

        val rawKey = generateKey()
        val keyHash = hashKey(rawKey)
        val keyPrefix = rawKey.take(DISPLAY_PREFIX_LENGTH)
        val normalizedTools = enabledTools.distinct().sorted()
        val normalizedResources = enabledResources.distinct().sorted()
        val now = Clock.System.now()
        val expiresAt = calculateExpiresAt(expiresInDays)

        val id = transaction {
            McpApiKeys.insert {
                it[McpApiKeys.organization_id] = organizationId
                it[McpApiKeys.name] = name.trim()
                it[McpApiKeys.key_hash] = keyHash
                it[McpApiKeys.key_prefix] = keyPrefix
                it[McpApiKeys.enabled_tools] = normalizedTools
                it[McpApiKeys.enabled_resources] = normalizedResources
                it[McpApiKeys.created_by] = userId
                it[McpApiKeys.created_at] = now
                it[McpApiKeys.expires_at] = expiresAt
                it[McpApiKeys.is_active] = true
            }[McpApiKeys.id]
        }

        return CreateMcpApiKeyResponse(
            id = id,
            name = name.trim(),
            keyPrefix = keyPrefix,
            key = rawKey,
            enabledTools = normalizedTools,
            enabledResources = normalizedResources,
            expiresAt = expiresAt?.toString(),
            createdAt = now.toString(),
        )
    }

    fun listKeys(organizationId: Int): List<McpApiKeyResponse> {
        return transaction {
            McpApiKeys
                .selectAll()
                .where {
                    (McpApiKeys.organization_id eq organizationId) and
                        (McpApiKeys.is_active eq true)
                }
                .orderBy(McpApiKeys.created_at, SortOrder.DESC)
                .map { row ->
                    McpApiKeyResponse(
                        id = row[McpApiKeys.id],
                        name = row[McpApiKeys.name],
                        keyPrefix = row[McpApiKeys.key_prefix],
                        enabledTools = row[McpApiKeys.enabled_tools],
                        enabledResources = row[McpApiKeys.enabled_resources],
                        lastUsedAt = row[McpApiKeys.last_used_at]?.toString(),
                        expiresAt = row[McpApiKeys.expires_at]?.toString(),
                        createdAt = row[McpApiKeys.created_at].toString(),
                    )
                }
        }
    }

    fun updateKey(
        organizationId: Int,
        keyId: Int,
        name: String?,
        enabledTools: List<String>?,
        enabledResources: List<String>?,
        expiresInDays: Int? = null,
    ): Boolean {
        name?.let { validateName(it) }
        val expiresAt = expiresInDays?.let { calculateExpiresAt(it) }

        return transaction {
            val updated = McpApiKeys.update({
                (McpApiKeys.id eq keyId) and
                    (McpApiKeys.organization_id eq organizationId) and
                    (McpApiKeys.is_active eq true)
            }) {
                name?.let { value -> it[McpApiKeys.name] = value.trim() }
                enabledTools?.let { tools ->
                    it[McpApiKeys.enabled_tools] = tools.distinct().sorted()
                }
                enabledResources?.let { resources ->
                    it[McpApiKeys.enabled_resources] = resources.distinct().sorted()
                }
                expiresAt?.let { value -> it[McpApiKeys.expires_at] = value }
            }
            updated > 0
        }
    }

    fun revokeKey(organizationId: Int, keyId: Int): Boolean {
        return transaction {
            val updated = McpApiKeys.update({
                (McpApiKeys.id eq keyId) and
                    (McpApiKeys.organization_id eq organizationId)
            }) {
                it[McpApiKeys.is_active] = false
            }
            updated > 0
        }
    }

    fun validateKey(key: String): McpApiKeyValidationResult? {
        if (!key.startsWith(KEY_PREFIX) || key.length < DISPLAY_PREFIX_LENGTH) {
            return null
        }

        val keyHash = hashKey(key)
        val keyPrefix = key.take(DISPLAY_PREFIX_LENGTH)
        val now = Clock.System.now()

        return transaction {
            val row = McpApiKeys
                .selectAll()
                .where {
                    (McpApiKeys.key_hash eq keyHash) and
                        (McpApiKeys.key_prefix eq keyPrefix) and
                        (McpApiKeys.is_active eq true)
                }
                .firstOrNull()
                ?: return@transaction null

            val expiresAt = row[McpApiKeys.expires_at]
            if (expiresAt != null && expiresAt < now) {
                return@transaction null
            }

            McpApiKeys.update({ McpApiKeys.id eq row[McpApiKeys.id] }) {
                it[McpApiKeys.last_used_at] = now
            }

            McpApiKeyValidationResult(
                keyId = row[McpApiKeys.id],
                organizationId = row[McpApiKeys.organization_id],
                userId = row[McpApiKeys.created_by],
                enabledTools = row[McpApiKeys.enabled_tools].toSet(),
                enabledResources = row[McpApiKeys.enabled_resources].toSet(),
            )
        }
    }

    private fun validateName(name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name is required" }
        require(trimmed.length <= KEY_NAME_MAX_LENGTH) {
            "Name must be at most $KEY_NAME_MAX_LENGTH characters"
        }
    }

    private fun calculateExpiresAt(expiresInDays: Int?): kotlin.time.Instant? {
        if (expiresInDays == null) {
            return null
        }
        require(expiresInDays > 0) { "Expiration must be at least one day" }
        return Clock.System.now().plus(expiresInDays * SECONDS_PER_DAY, DateTimeUnit.SECOND)
    }

    private fun generateKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(KEY_RANDOM_BYTES)
        random.nextBytes(bytes)
        return KEY_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
