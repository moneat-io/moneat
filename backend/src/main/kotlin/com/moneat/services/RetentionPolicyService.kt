package com.moneat.services

import com.moneat.models.Organizations
import com.moneat.models.PricingTier
import com.moneat.models.Projects
import com.moneat.models.Systems
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
                Projects.select { Projects.id eq projectId }
                    .firstOrNull()
                    ?.get(Projects.organization_id)
            }
            organizationId?.let { getRetentionDaysForOrganization(it) }
        }
    }

    suspend fun getRetentionDaysForSystem(systemId: UUID): Int? {
        return CacheService.cached("cache:retention:system:$systemId", 300) {
            val organizationId = transaction {
                Systems.select { Systems.id eq systemId }
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
}
