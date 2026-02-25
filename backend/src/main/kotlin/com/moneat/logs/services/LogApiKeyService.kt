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

package com.moneat.logs.services

import com.moneat.logs.models.CreateLogApiKeyResponse
import com.moneat.logs.models.LogApiKeyResponse
import com.moneat.shared.models.LogApiKeys
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import java.security.MessageDigest
import java.security.SecureRandom

private const val KEY_PREFIX = "mlog_"
private const val KEY_PREFIX_LENGTH = 5
private const val KEY_RANDOM_BYTES = 32
private const val DISPLAY_PREFIX_LENGTH = 12

class LogApiKeyService {

    fun createKey(
        organizationId: Int,
        name: String,
        createdBy: Int?
    ): CreateLogApiKeyResponse {
        val rawKey = generateKey()
        val keyHash = hashKey(rawKey)
        val keyPrefix = rawKey.take(DISPLAY_PREFIX_LENGTH)
        val now = Clock.System.now()

        val id =
            transaction {
                LogApiKeys.insert {
                    it[LogApiKeys.organization_id] = organizationId
                    it[LogApiKeys.name] = name
                    it[LogApiKeys.key_hash] = keyHash
                    it[LogApiKeys.key_prefix] = keyPrefix
                    it[LogApiKeys.created_by] = createdBy
                    it[LogApiKeys.created_at] = now
                    it[LogApiKeys.is_active] = true
                }[LogApiKeys.id]
            }

        return CreateLogApiKeyResponse(
            id = id,
            name = name,
            keyPrefix = keyPrefix,
            key = rawKey,
            createdAt = now.toString()
        )
    }

    /**
     * Validate log API key and return organization ID if valid.
     */
    fun validateKey(key: String): Int? {
        if (!key.startsWith(KEY_PREFIX) || key.length < DISPLAY_PREFIX_LENGTH) {
            return null
        }
        val keyHash = hashKey(key)
        val keyPrefix = key.take(DISPLAY_PREFIX_LENGTH)

        return transaction {
            val row =
                LogApiKeys
                    .selectAll()
                    .where {
                        (LogApiKeys.key_hash eq keyHash) and
                            (LogApiKeys.key_prefix eq keyPrefix) and
                            (LogApiKeys.is_active eq true)
                    }
                    .firstOrNull()
                    ?: return@transaction null

            // Update last_used_at
            LogApiKeys
                .update({ LogApiKeys.id eq row[LogApiKeys.id] }) {
                    it[LogApiKeys.last_used_at] = Clock.System.now()
                }

            row[LogApiKeys.organization_id]
        }
    }

    fun listKeys(organizationId: Int): List<LogApiKeyResponse> {
        return transaction {
            LogApiKeys
                .selectAll()
                .where {
                    (LogApiKeys.organization_id eq organizationId) and
                        (LogApiKeys.is_active eq true)
                }
                .map { row ->
                    LogApiKeyResponse(
                        id = row[LogApiKeys.id],
                        name = row[LogApiKeys.name],
                        keyPrefix = row[LogApiKeys.key_prefix],
                        createdAt = row[LogApiKeys.created_at].toString(),
                        lastUsedAt = row[LogApiKeys.last_used_at]?.toString()
                    )
                }
        }
    }

    fun deleteKey(organizationId: Int, keyId: Int): Boolean {
        return transaction {
            val updated =
                LogApiKeys
                    .update({
                        (LogApiKeys.id eq keyId) and
                            (LogApiKeys.organization_id eq organizationId)
                    }) {
                        it[LogApiKeys.is_active] = false
                    }
            updated > 0
        }
    }

    fun hasOrgAccess(keyId: Int, organizationId: Int): Boolean {
        return transaction {
            LogApiKeys
                .selectAll()
                .where {
                    (LogApiKeys.id eq keyId) and
                        (LogApiKeys.organization_id eq organizationId)
                }
                .firstOrNull() != null
        }
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
