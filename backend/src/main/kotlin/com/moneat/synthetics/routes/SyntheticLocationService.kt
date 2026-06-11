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

package com.moneat.synthetics.routes

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Clock

private const val LOCATION_KEY_PREFIX = "mloc_"
private const val LOCATION_KEY_RANDOM_BYTES = 32
private const val WORKER_ONLINE_WINDOW_SECONDS = 120L

/** Manages managed (platform-global) + private (per-org) probe locations and their keys. */
class SyntheticLocationService {

    /** Managed locations (org NULL) plus this org's private locations. */
    fun listLocations(organizationId: Int): List<SyntheticLocationResponse> =
        transaction {
            SyntheticLocations
                .selectAll()
                .where {
                    SyntheticLocations.organizationId.isNull() or
                        (SyntheticLocations.organizationId eq organizationId)
                }
                .orderBy(SyntheticLocations.locationType to SortOrder.ASC, SyntheticLocations.name to SortOrder.ASC)
                .map { rowToResponse(it) }
        }

    fun createPrivateLocation(
        organizationId: Int,
        request: CreatePrivateLocationRequest
    ): CreatePrivateLocationResponse {
        val rawKey = generateKey()
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            SyntheticLocations.insert {
                it[SyntheticLocations.id] = id
                it[SyntheticLocations.organizationId] = organizationId
                it[code] = request.code
                it[name] = request.name
                it[region] = request.region
                it[locationType] = "private"
                it[active] = true
                it[keyHash] = hashKey(rawKey)
                it[workerCount] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        val location = getLocation(id, organizationId)!!
        return CreatePrivateLocationResponse(location = location, key = rawKey)
    }

    fun getLocation(id: UUID, organizationId: Int): SyntheticLocationResponse? =
        transaction {
            SyntheticLocations
                .selectAll()
                .where {
                    (SyntheticLocations.id eq id) and
                        (
                            SyntheticLocations.organizationId.isNull() or
                                (SyntheticLocations.organizationId eq organizationId)
                            )
                }
                .firstOrNull()
                ?.let { rowToResponse(it) }
        }

    /** Deletes a private location owned by this org. Managed locations cannot be deleted. */
    fun deletePrivateLocation(id: UUID, organizationId: Int): Boolean =
        transaction {
            SyntheticLocations.deleteWhere {
                (SyntheticLocations.id eq id) and
                    (SyntheticLocations.organizationId eq organizationId) and
                    (locationType eq "private")
            } > 0
        }

    /** Resolves a probe key to its (locationCode, organizationId), recording a check-in. */
    fun authenticateProbe(key: String, expectedCode: String): ProbeIdentity? {
        if (!key.startsWith(LOCATION_KEY_PREFIX)) return null
        val keyHash = hashKey(key)
        return transaction {
            val row = SyntheticLocations
                .selectAll()
                .where {
                    (SyntheticLocations.keyHash eq keyHash) and
                        (SyntheticLocations.code eq expectedCode) and
                        (SyntheticLocations.active eq true)
                }
                .firstOrNull() ?: return@transaction null
            val orgId = row[SyntheticLocations.organizationId] ?: return@transaction null
            SyntheticLocations.update({ SyntheticLocations.id eq row[SyntheticLocations.id] }) {
                it[lastSeenAt] = Clock.System.now()
                it[workerCount] = 1
            }
            ProbeIdentity(locationCode = row[SyntheticLocations.code], organizationId = orgId)
        }
    }

    /** All managed (platform-global) location codes — these run on the built-in worker. */
    fun managedCodes(): Set<String> =
        transaction {
            SyntheticLocations
                .selectAll()
                .where { SyntheticLocations.organizationId.isNull() }
                .map { it[SyntheticLocations.code] }
                .toSet()
        }

    private fun rowToResponse(row: ResultRow): SyntheticLocationResponse {
        val type = row[SyntheticLocations.locationType]
        val lastSeen = row[SyntheticLocations.lastSeenAt]
        val workers = if (type == "managed") {
            1
        } else {
            val online = lastSeen != null &&
                (Clock.System.now() - lastSeen).inWholeSeconds <= WORKER_ONLINE_WINDOW_SECONDS
            if (online) row[SyntheticLocations.workerCount].coerceAtLeast(1) else 0
        }
        return SyntheticLocationResponse(
            id = row[SyntheticLocations.id].toString(),
            code = row[SyntheticLocations.code],
            name = row[SyntheticLocations.name],
            region = row[SyntheticLocations.region],
            type = type,
            active = row[SyntheticLocations.active],
            workerCount = workers,
            lastSeenAt = lastSeen?.toEpochMilliseconds()
        )
    }

    private fun generateKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(LOCATION_KEY_RANDOM_BYTES)
        random.nextBytes(bytes)
        return LOCATION_KEY_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Identity resolved from a private-location probe key. */
data class ProbeIdentity(
    val locationCode: String,
    val organizationId: Int
)
