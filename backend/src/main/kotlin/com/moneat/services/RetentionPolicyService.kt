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

package com.moneat.services

import com.moneat.models.Organizations
import com.moneat.models.PricingTier
import com.moneat.models.Projects
import com.moneat.models.Systems
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

class RetentionPolicyService(
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    suspend fun getRetentionDaysForOrganization(organizationId: Int): Int {
        return CacheService.cached("cache:retention:org:$organizationId", 300) {
            pricingTierService.getEffectiveTierForOrganization(organizationId).tier.retentionDays
        }
    }

    suspend fun getRetentionDaysForProject(projectId: Long): Int? {
        return CacheService.cached("cache:retention:project:$projectId", 300) {
            val organizationId = transaction {
                Projects.selectAll().where { Projects.id eq projectId }
                    .firstOrNull()
                    ?.get(Projects.organization_id)
            }
            organizationId?.let { getRetentionDaysForOrganization(it) }
        }
    }

    suspend fun getRetentionDaysForSystem(systemId: UUID): Int? {
        return CacheService.cached("cache:retention:system:$systemId", 300) {
            val organizationId = transaction {
                Systems.selectAll().where { Systems.id eq systemId }
                    .firstOrNull()
                    ?.get(Systems.organization_id)
            }
            organizationId?.let { getRetentionDaysForOrganization(it) }
        }
    }

    suspend fun getRetentionDaysByOrganization(): Map<Int, Int> {
        val orgIds = transaction { Organizations.selectAll().map { it[Organizations.id] } }
        if (orgIds.isEmpty()) return emptyMap()

        val retentionByOrg = LinkedHashMap<Int, Int>(orgIds.size)
        for (orgId in orgIds) {
            retentionByOrg[orgId] = runCatching {
                getRetentionDaysForOrganization(orgId)
            }.getOrDefault(PricingTier.FREE.retentionDays)
        }
        return retentionByOrg
    }

    suspend fun getLogRetentionDaysForOrganization(organizationId: Int): Int {
        return CacheService.cached("cache:log_retention:org:$organizationId", 300) {
            pricingTierService.getEffectiveTierForOrganization(organizationId).tier.logRetentionDays
        }
    }

    suspend fun getLogRetentionDaysForProject(projectId: Long): Int? {
        return CacheService.cached("cache:log_retention:project:$projectId", 300) {
            val organizationId = transaction {
                Projects.selectAll().where { Projects.id eq projectId }
                    .firstOrNull()
                    ?.get(Projects.organization_id)
            }
            organizationId?.let { getLogRetentionDaysForOrganization(it) }
        }
    }

    suspend fun getLogRetentionDaysByOrganization(): Map<Int, Int> {
        val orgIds = transaction { Organizations.selectAll().map { it[Organizations.id] } }
        if (orgIds.isEmpty()) return emptyMap()

        val logRetentionByOrg = LinkedHashMap<Int, Int>(orgIds.size)
        for (orgId in orgIds) {
            logRetentionByOrg[orgId] = runCatching {
                getLogRetentionDaysForOrganization(orgId)
            }.getOrDefault(3) // Default to FREE tier log retention (3 days)
        }
        return logRetentionByOrg
    }
}
