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

import com.moneat.shared.models.OrganizationTeams
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
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
    val team_id = integer("team_id").references(OrganizationTeams.id, onDelete = ReferenceOption.CASCADE)
    val updated_by = varchar("updated_by", 320)
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(organization_id, resource_id)
    }
}

/** Persistent store for resource ownership claims, keyed by catalog resource id. */
interface ResourceOwnershipRepository {
    /** All ownership claims for an organization, keyed by catalog resource id, valued by internal team id. */
    fun listByOrganization(organizationId: Int): Map<String, Int>

    /** Create or replace the ownership claim for one resource. */
    fun upsert(organizationId: Int, resourceId: String, teamId: Int, updatedBy: String)

    /** Delete the ownership claim for one resource. */
    fun delete(organizationId: Int, resourceId: String): Boolean

    /** Resolve the owning team's default escalation policy for one catalog resource. */
    fun escalationPolicyIdForResource(organizationId: Int, resourceId: String): Int?
}

class ResourceOwnershipRepositoryImpl : ResourceOwnershipRepository {
    override fun listByOrganization(organizationId: Int): Map<String, Int> =
        transaction {
            ResourceOwnership
                .selectAll()
                .where { ResourceOwnership.organization_id eq organizationId }
                .associate { row ->
                    row[ResourceOwnership.resource_id] to row[ResourceOwnership.team_id]
                }
        }

    override fun upsert(organizationId: Int, resourceId: String, teamId: Int, updatedBy: String) {
        val now = Clock.System.now()
        transaction {
            requireTeamBelongsToOrganization(organizationId, teamId)
            if (updateOwnership(organizationId, resourceId, teamId, updatedBy, now) == 0) {
                insertOwnershipIfMissing(organizationId, resourceId, teamId, updatedBy, now)
                check(updateOwnership(organizationId, resourceId, teamId, updatedBy, now) > 0) {
                    "Resource ownership upsert did not persist for resource $resourceId"
                }
            }
        }
    }

    override fun delete(organizationId: Int, resourceId: String): Boolean =
        transaction {
            ResourceOwnership.deleteWhere {
                (ResourceOwnership.organization_id eq organizationId) and
                    (ResourceOwnership.resource_id eq resourceId)
            } > 0
        }

    override fun escalationPolicyIdForResource(organizationId: Int, resourceId: String): Int? =
        transaction {
            ResourceOwnership
                .join(
                    otherTable = OrganizationTeams,
                    joinType = JoinType.INNER,
                    onColumn = ResourceOwnership.team_id,
                    otherColumn = OrganizationTeams.id,
                )
                .selectAll()
                .where {
                    (ResourceOwnership.organization_id eq organizationId) and
                        (ResourceOwnership.resource_id eq resourceId) and
                        (OrganizationTeams.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(OrganizationTeams.escalationPolicyId)
        }

    private fun updateOwnership(
        organizationId: Int,
        resourceId: String,
        teamId: Int,
        updatedBy: String,
        updatedAt: Instant,
    ): Int =
        ResourceOwnership.update({
            (ResourceOwnership.organization_id eq organizationId) and (ResourceOwnership.resource_id eq resourceId)
        }) {
            it[ResourceOwnership.team_id] = teamId
            it[updated_by] = updatedBy
            it[updated_at] = updatedAt
        }

    private fun requireTeamBelongsToOrganization(organizationId: Int, teamId: Int) {
        val exists =
            OrganizationTeams
                .selectAll()
                .where {
                    (OrganizationTeams.organizationId eq organizationId) and
                        (OrganizationTeams.id eq teamId)
                }
                .any()
        require(exists) { "Team does not belong to organization" }
    }

    private fun insertOwnershipIfMissing(
        organizationId: Int,
        resourceId: String,
        teamId: Int,
        updatedBy: String,
        updatedAt: Instant,
    ) {
        ResourceOwnership.insertIgnore {
            it[organization_id] = organizationId
            it[resource_id] = resourceId
            it[team_id] = teamId
            it[updated_by] = updatedBy
            it[updated_at] = updatedAt
        }
    }
}

/** Default no-op store used when no persistence is wired (tests, OSS without a DB). */
object NoopResourceOwnershipRepository : ResourceOwnershipRepository {
    override fun listByOrganization(organizationId: Int): Map<String, Int> = emptyMap()
    override fun upsert(organizationId: Int, resourceId: String, teamId: Int, updatedBy: String) = Unit
    override fun delete(organizationId: Int, resourceId: String): Boolean = false
    override fun escalationPolicyIdForResource(organizationId: Int, resourceId: String): Int? = null
}
