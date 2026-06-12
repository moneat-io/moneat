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

package com.moneat.otlp.services

import com.moneat.otlp.models.CreateOtlpApiKeyResponse
import com.moneat.otlp.models.OtlpApiKeyResponse
import com.moneat.shared.models.OtlpApiKeys
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val NEW_KEY_PREFIX = "motlp_"
private const val LEGACY_KEY_PREFIX = "mlog_"
private const val KEY_RANDOM_BYTES = 32
private const val DISPLAY_PREFIX_LENGTH = 12
private val LAST_USED_UPDATE_THRESHOLD = 5.minutes

class OtlpApiKeyService {

    fun createKey(
        organizationId: Int,
        name: String,
        createdBy: Int?
    ): CreateOtlpApiKeyResponse {
        val rawKey = generateKey()
        val keyHash = hashKey(rawKey)
        val keyPrefix = rawKey.take(DISPLAY_PREFIX_LENGTH)
        val now = Clock.System.now()

        val row =
            transaction {
                OtlpApiKeys.insert {
                    it[OtlpApiKeys.organization_id] = organizationId
                    it[OtlpApiKeys.name] = name
                    it[OtlpApiKeys.key_hash] = keyHash
                    it[OtlpApiKeys.key_prefix] = keyPrefix
                    it[OtlpApiKeys.created_by] = createdBy
                    it[OtlpApiKeys.created_at] = now
                    it[OtlpApiKeys.is_active] = true
                }
            }

        return CreateOtlpApiKeyResponse(
            id = row[OtlpApiKeys.resource_id].toString(),
            name = name,
            keyPrefix = keyPrefix,
            key = rawKey,
            createdAt = now.toString()
        )
    }

    fun validateKey(key: String): Int? {
        val hasValidPrefix = key.startsWith(NEW_KEY_PREFIX) || key.startsWith(LEGACY_KEY_PREFIX)
        if (!hasValidPrefix || key.length < DISPLAY_PREFIX_LENGTH) {
            return null
        }
        val keyHash = hashKey(key)
        val keyPrefix = key.take(DISPLAY_PREFIX_LENGTH)

        return transaction {
            val row =
                OtlpApiKeys
                    .selectAll()
                    .where {
                        (OtlpApiKeys.key_hash eq keyHash) and
                            (OtlpApiKeys.key_prefix eq keyPrefix) and
                            (OtlpApiKeys.is_active eq true)
                    }
                    .firstOrNull()
                    ?: return@transaction null

            val now = Clock.System.now()
            val lastUsed = row[OtlpApiKeys.last_used_at]
            if (lastUsed == null || (now - lastUsed) > LAST_USED_UPDATE_THRESHOLD) {
                OtlpApiKeys
                    .update({ OtlpApiKeys.id eq row[OtlpApiKeys.id] }) {
                        it[OtlpApiKeys.last_used_at] = now
                    }
            }

            row[OtlpApiKeys.organization_id]
        }
    }

    fun listKeys(organizationId: Int): List<OtlpApiKeyResponse> {
        return transaction {
            OtlpApiKeys
                .selectAll()
                .where {
                    (OtlpApiKeys.organization_id eq organizationId) and
                        (OtlpApiKeys.is_active eq true)
                }
                .map { row ->
                    OtlpApiKeyResponse(
                        id = row[OtlpApiKeys.resource_id].toString(),
                        name = row[OtlpApiKeys.name],
                        keyPrefix = row[OtlpApiKeys.key_prefix],
                        createdAt = row[OtlpApiKeys.created_at].toString(),
                        lastUsedAt = row[OtlpApiKeys.last_used_at]?.toString()
                    )
                }
        }
    }

    fun deleteKey(organizationId: Int, keyResourceId: Uuid): Boolean {
        return transaction {
            val updated =
                OtlpApiKeys
                    .update({
                        (OtlpApiKeys.resource_id eq keyResourceId) and
                            (OtlpApiKeys.organization_id eq organizationId)
                    }) {
                        it[OtlpApiKeys.is_active] = false
                    }
            updated > 0
        }
    }

    fun hasOrgAccess(keyId: Int, organizationId: Int): Boolean {
        return transaction {
            OtlpApiKeys
                .selectAll()
                .where {
                    (OtlpApiKeys.id eq keyId) and
                        (OtlpApiKeys.organization_id eq organizationId) and
                        (OtlpApiKeys.is_active eq true)
                }
                .firstOrNull() != null
        }
    }

    private fun generateKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(KEY_RANDOM_BYTES)
        random.nextBytes(bytes)
        return NEW_KEY_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
