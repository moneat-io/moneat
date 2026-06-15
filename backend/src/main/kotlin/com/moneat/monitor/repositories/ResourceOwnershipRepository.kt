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

import com.moneat.monitor.models.CatalogOwner
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant

/** Exposed table backing per-resource ownership claims. */
object ResourceOwnership : Table("resource_ownership") {
    val id = integer("id").autoIncrement()
    val organization_id = integer("organization_id")
    val resource_id = varchar("resource_id", 512)
    val team = varchar("team", 200)
    val oncall = varchar("oncall", 200)
    val slack = varchar("slack", 200)
    val repo = varchar("repo", 300)
    val updated_by = varchar("updated_by", 320)
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Persistent store for resource ownership claims, keyed by catalog resource id. */
interface ResourceOwnershipRepository {
    /** All ownership claims for an organization, keyed by catalog resource id. */
    fun listByOrganization(organizationId: Int): Map<String, CatalogOwner>

    /** Create or replace the ownership claim for one resource. */
    fun upsert(organizationId: Int, resourceId: String, owner: CatalogOwner, updatedBy: String)
}

class ResourceOwnershipRepositoryImpl : ResourceOwnershipRepository {
    override fun listByOrganization(organizationId: Int): Map<String, CatalogOwner> =
        transaction {
            ResourceOwnership
                .selectAll()
                .where { ResourceOwnership.organization_id eq organizationId }
                .associate { row ->
                    row[ResourceOwnership.resource_id] to CatalogOwner(
                        team = row[ResourceOwnership.team],
                        oncall = row[ResourceOwnership.oncall],
                        slack = row[ResourceOwnership.slack],
                        repo = row[ResourceOwnership.repo],
                    )
                }
        }

    override fun upsert(organizationId: Int, resourceId: String, owner: CatalogOwner, updatedBy: String) {
        val now = Clock.System.now()
        transaction {
            if (updateOwnership(organizationId, resourceId, owner, updatedBy, now) == 0) {
                insertOwnershipIfMissing(organizationId, resourceId, owner, updatedBy, now)
                check(updateOwnership(organizationId, resourceId, owner, updatedBy, now) > 0) {
                    "Resource ownership upsert did not persist for resource $resourceId"
                }
            }
        }
    }

    private fun updateOwnership(
        organizationId: Int,
        resourceId: String,
        owner: CatalogOwner,
        updatedBy: String,
        updatedAt: Instant,
    ): Int =
        ResourceOwnership.update({
            (ResourceOwnership.organization_id eq organizationId) and (ResourceOwnership.resource_id eq resourceId)
        }) {
            it[team] = owner.team
            it[oncall] = owner.oncall
            it[slack] = owner.slack
            it[repo] = owner.repo
            it[updated_by] = updatedBy
            it[updated_at] = updatedAt
        }

    private fun insertOwnershipIfMissing(
        organizationId: Int,
        resourceId: String,
        owner: CatalogOwner,
        updatedBy: String,
        updatedAt: Instant,
    ) {
        ResourceOwnership.insertIgnore {
            it[organization_id] = organizationId
            it[resource_id] = resourceId
            it[team] = owner.team
            it[oncall] = owner.oncall
            it[slack] = owner.slack
            it[repo] = owner.repo
            it[updated_by] = updatedBy
            it[updated_at] = updatedAt
        }
    }
}

/** Default no-op store used when no persistence is wired (tests, OSS without a DB). */
object NoopResourceOwnershipRepository : ResourceOwnershipRepository {
    override fun listByOrganization(organizationId: Int): Map<String, CatalogOwner> = emptyMap()
    override fun upsert(organizationId: Int, resourceId: String, owner: CatalogOwner, updatedBy: String) = Unit
}
