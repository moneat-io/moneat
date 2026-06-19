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

import com.moneat.monitor.models.AgentApiKeyResponse
import com.moneat.monitor.models.CreateAgentApiKeyResponse
import com.moneat.shared.models.AgentApiKeys
import com.moneat.shared.services.toUuidOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.uuid.Uuid
import kotlin.time.Clock

private const val KEY_PREFIX = "magt_"
private const val KEY_RANDOM_BYTES = 32
private const val DISPLAY_PREFIX_LENGTH = 12

class AgentApiKeyService {

    fun createKey(organizationId: Int, name: String, createdBy: Int?): CreateAgentApiKeyResponse {
        val rawKey = generateKey()
        val keyHash = hashKey(rawKey)
        val keyPrefix = rawKey.take(DISPLAY_PREFIX_LENGTH)
        val now = Clock.System.now()

        val row = transaction {
            AgentApiKeys.insert {
                it[AgentApiKeys.organization_id] = organizationId
                it[AgentApiKeys.name] = name
                it[AgentApiKeys.key_hash] = keyHash
                it[AgentApiKeys.key_prefix] = keyPrefix
                it[AgentApiKeys.created_by] = createdBy
                it[AgentApiKeys.created_at] = now
                it[AgentApiKeys.is_active] = true
            }
        }

        return CreateAgentApiKeyResponse(
            id = row[AgentApiKeys.resource_id].toString(),
            name = name,
            keyPrefix = keyPrefix,
            key = rawKey,
            createdAt = now.toString()
        )
    }

    fun validateKey(key: String): Int? {
        if (!key.startsWith(KEY_PREFIX) || key.length < DISPLAY_PREFIX_LENGTH) return null
        val keyHash = hashKey(key)
        val keyPrefix = key.take(DISPLAY_PREFIX_LENGTH)

        return transaction {
            val row = AgentApiKeys
                .selectAll()
                .where {
                    (AgentApiKeys.key_hash eq keyHash) and
                        (AgentApiKeys.key_prefix eq keyPrefix) and
                        (AgentApiKeys.is_active eq true)
                }
                .firstOrNull() ?: return@transaction null

            AgentApiKeys.update({ AgentApiKeys.id eq row[AgentApiKeys.id] }) {
                it[AgentApiKeys.last_used_at] = Clock.System.now()
            }

            row[AgentApiKeys.organization_id]
        }
    }

    fun listKeys(organizationId: Int): List<AgentApiKeyResponse> {
        return transaction {
            AgentApiKeys
                .selectAll()
                .where {
                    (AgentApiKeys.organization_id eq organizationId) and
                        (AgentApiKeys.is_active eq true)
                }
                .map { row ->
                    AgentApiKeyResponse(
                        id = row[AgentApiKeys.resource_id].toString(),
                        name = row[AgentApiKeys.name],
                        keyPrefix = row[AgentApiKeys.key_prefix],
                        createdAt = row[AgentApiKeys.created_at].toString(),
                        lastUsedAt = row[AgentApiKeys.last_used_at]?.toString()
                    )
                }
        }
    }

    fun deleteKey(organizationId: Int, keyResourceId: Uuid): Boolean {
        return transaction {
            val updated = AgentApiKeys.update({
                (AgentApiKeys.resource_id eq keyResourceId) and
                    (AgentApiKeys.organization_id eq organizationId)
            }) {
                it[AgentApiKeys.is_active] = false
            }
            updated > 0
        }
    }

    fun deleteKey(organizationId: Int, keyResourceId: String): Boolean {
        val parsedId = keyResourceId.toUuidOrNull() ?: return false
        return deleteKey(organizationId, parsedId)
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
