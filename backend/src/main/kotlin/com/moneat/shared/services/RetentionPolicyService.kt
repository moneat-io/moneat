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

package com.moneat.shared.services

import com.moneat.billing.models.PricingTier
import com.moneat.billing.services.PricingTierService
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

class RetentionPolicyService(
    private val pricingTierService: PricingTierService = PricingTierService()
) {
    companion object {
        const val RETENTION_CACHE_TTL_SECONDS = 300L
        private const val FREE_TIER_LOG_RETENTION_DAYS = 3
        private const val DEFAULT_ANALYTICS_RETENTION_DAYS = 1095
    }

    suspend fun getRetentionDaysForOrganization(organizationId: Int): Int {
        return CacheService.cached("cache:retention:org:$organizationId", RETENTION_CACHE_TTL_SECONDS) {
            pricingTierService.getEffectiveTierForOrganization(organizationId).tier.retentionDays
        }
    }

    suspend fun getRetentionDaysForProject(projectId: Long): Int? {
        return CacheService.cached("cache:retention:project:$projectId", RETENTION_CACHE_TTL_SECONDS) {
            val organizationId =
                transaction {
                    Projects
                        .selectAll()
                        .where { Projects.id eq projectId }
                        .firstOrNull()
                        ?.get(Projects.organization_id)
                }
            organizationId?.let { getRetentionDaysForOrganization(it) }
        }
    }

    suspend fun getRetentionDaysForHost(hostId: Int): Int? {
        return CacheService.cached("cache:retention:host:$hostId", RETENTION_CACHE_TTL_SECONDS) {
            val organizationId =
                transaction {
                    Hosts
                        .selectAll()
                        .where { Hosts.id eq hostId }
                        .firstOrNull()
                        ?.get(Hosts.organization_id)
                }
            organizationId?.let { getRetentionDaysForOrganization(it) }
        }
    }

    suspend fun getRetentionDaysByOrganization(): Map<Int, Int> {
        val orgIds = transaction { Organizations.selectAll().map { it[Organizations.id] } }
        if (orgIds.isEmpty()) return emptyMap()

        val retentionByOrg = LinkedHashMap<Int, Int>(orgIds.size)
        for (orgId in orgIds) {
            retentionByOrg[orgId] =
                runCatching {
                    getRetentionDaysForOrganization(orgId)
                }.getOrDefault(PricingTier.FREE.retentionDays)
        }
        return retentionByOrg
    }

    suspend fun getLogRetentionDaysForOrganization(organizationId: Int): Int {
        return CacheService.cached("cache:log_retention:org:$organizationId", RETENTION_CACHE_TTL_SECONDS) {
            pricingTierService.getEffectiveTierForOrganization(organizationId).tier.logRetentionDays
        }
    }

    suspend fun getLogRetentionDaysForProject(projectId: Long): Int? {
        return CacheService.cached("cache:log_retention:project:$projectId", RETENTION_CACHE_TTL_SECONDS) {
            val organizationId =
                transaction {
                    Projects
                        .selectAll()
                        .where { Projects.id eq projectId }
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
            logRetentionByOrg[orgId] =
                runCatching {
                    getLogRetentionDaysForOrganization(orgId)
                }.getOrDefault(FREE_TIER_LOG_RETENTION_DAYS) // Default to FREE tier log retention
        }
        return logRetentionByOrg
    }

    suspend fun getReplayRetentionDaysForOrganization(organizationId: Int): Int {
        return CacheService.cached("cache:replay_retention:org:$organizationId", RETENTION_CACHE_TTL_SECONDS) {
            pricingTierService.getEffectiveTierForOrganization(organizationId).tier.replayRetentionDays
        }
    }

    suspend fun getReplayRetentionDaysByOrganization(): Map<Int, Int> {
        val orgIds = transaction { Organizations.selectAll().map { it[Organizations.id] } }
        if (orgIds.isEmpty()) return emptyMap()
        val replayRetentionByOrg = LinkedHashMap<Int, Int>(orgIds.size)
        for (orgId in orgIds) {
            replayRetentionByOrg[orgId] =
                runCatching { getReplayRetentionDaysForOrganization(orgId) }
                    .getOrDefault(PricingTier.FREE.retentionDays)
        }
        return replayRetentionByOrg
    }

    suspend fun getLlmRetentionDaysForOrganization(organizationId: Int): Int {
        return CacheService.cached("cache:llm_retention:org:$organizationId", RETENTION_CACHE_TTL_SECONDS) {
            pricingTierService.getEffectiveTierForOrganization(organizationId).tier.llmRetentionDays
        }
    }

    suspend fun getLlmRetentionDaysByOrganization(): Map<Int, Int> {
        val orgIds = transaction { Organizations.selectAll().map { it[Organizations.id] } }
        if (orgIds.isEmpty()) return emptyMap()
        val llmRetentionByOrg = LinkedHashMap<Int, Int>(orgIds.size)
        for (orgId in orgIds) {
            llmRetentionByOrg[orgId] =
                runCatching { getLlmRetentionDaysForOrganization(orgId) }
                    .getOrDefault(PricingTier.FREE.retentionDays)
        }
        return llmRetentionByOrg
    }

    suspend fun getAnalyticsRetentionDaysForOrganization(organizationId: Int): Int {
        return CacheService.cached("cache:analytics_retention:org:$organizationId", RETENTION_CACHE_TTL_SECONDS) {
            pricingTierService.getEffectiveTierForOrganization(organizationId).tier.analyticsRetentionDays
        }
    }

    suspend fun getAnalyticsRetentionDaysByOrganization(): Map<Int, Int> {
        val orgIds = transaction { Organizations.selectAll().map { it[Organizations.id] } }
        if (orgIds.isEmpty()) return emptyMap()
        val analyticsRetentionByOrg = LinkedHashMap<Int, Int>(orgIds.size)
        for (orgId in orgIds) {
            analyticsRetentionByOrg[orgId] =
                runCatching { getAnalyticsRetentionDaysForOrganization(orgId) }
                    .getOrDefault(DEFAULT_ANALYTICS_RETENTION_DAYS) // Default 3 years for analytics
        }
        return analyticsRetentionByOrg
    }
}
