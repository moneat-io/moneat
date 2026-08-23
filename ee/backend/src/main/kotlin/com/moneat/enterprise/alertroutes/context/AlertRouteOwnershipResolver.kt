// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.context

import com.moneat.monitor.repositories.ResourceOwnership
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OrganizationTeams
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Resolves service, team, and catalog ownership for an alert from its approved metadata. */
fun interface AlertRouteOwnershipResolver {
    fun resolve(organizationId: Int, metadata: Map<String, String>): AlertRouteOwnership

    companion object {
        val NONE = AlertRouteOwnershipResolver { _, _ -> AlertRouteOwnership.NONE }
    }
}

/**
 * Reads ownership claims from the shared catalog ownership table and organization teams.
 *
 * A catalog resource id resolves to its owning team; otherwise a team name or slug in metadata is
 * matched directly. The owning team's default escalation policy backs OWNERSHIP_DERIVED targets.
 */
class DatabaseAlertRouteOwnershipResolver : AlertRouteOwnershipResolver {
    override fun resolve(organizationId: Int, metadata: Map<String, String>): AlertRouteOwnership {
        val catalogResourceId = metadata.nonBlankValue(CATALOG_RESOURCE_KEY)
        val serviceName = metadata.nonBlankValue(SERVICE_KEY, SERVICE_NAME_KEY)
        val teamHint = metadata.nonBlankValue(TEAM_KEY)
        if (catalogResourceId == null && teamHint == null && serviceName == null) {
            return AlertRouteOwnership.NONE
        }
        return transaction {
            val team =
                catalogResourceId?.let { teamForCatalogResource(organizationId, it) }
                    ?: teamHint?.let { teamByNameOrSlug(organizationId, it) }
            val escalationPolicyId = team?.get(OrganizationTeams.escalationPolicyId)
            AlertRouteOwnership(
                serviceName = serviceName,
                teamId = team?.get(OrganizationTeams.id)?.value,
                teamResourceId = team?.get(OrganizationTeams.resourceId),
                teamName = team?.get(OrganizationTeams.name) ?: teamHint,
                catalogResourceId = catalogResourceId,
                escalationPolicyId = escalationPolicyId,
                escalationPolicyResourceId = escalationPolicyId?.let(::escalationPolicyResourceId),
            )
        }
    }

    private fun escalationPolicyResourceId(escalationPolicyId: Int): Uuid? =
        EscalationPolicies
            .selectAll()
            .where { EscalationPolicies.id eq escalationPolicyId }
            .limit(1)
            .firstOrNull()
            ?.get(EscalationPolicies.resourceId)

    private fun teamForCatalogResource(organizationId: Int, catalogResourceId: String): ResultRow? {
        val teamId =
            ResourceOwnership
                .selectAll()
                .where {
                    (ResourceOwnership.organization_id eq organizationId) and
                        (ResourceOwnership.resource_id eq catalogResourceId)
                }.limit(1)
                .firstOrNull()
                ?.get(ResourceOwnership.team_id)
                ?: return null
        return OrganizationTeams
            .selectAll()
            .where {
                (OrganizationTeams.organizationId eq organizationId) and
                    (OrganizationTeams.id eq teamId)
            }.limit(1)
            .firstOrNull()
    }

    private fun teamByNameOrSlug(organizationId: Int, hint: String): ResultRow? {
        val normalized = hint.trim()
        if (normalized.isEmpty()) return null
        return OrganizationTeams
            .selectAll()
            .where { OrganizationTeams.organizationId eq organizationId }
            .firstOrNull { row ->
                row[OrganizationTeams.name].equals(normalized, ignoreCase = true) ||
                    row[OrganizationTeams.slug].equals(normalized, ignoreCase = true)
            }
    }

    private fun Map<String, String>.nonBlankValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> get(key)?.trim()?.takeIf(String::isNotEmpty) }

    private companion object {
        const val CATALOG_RESOURCE_KEY = "catalog_resource_id"
        const val SERVICE_KEY = "service"
        const val SERVICE_NAME_KEY = "service_name"
        const val TEAM_KEY = "team"
    }
}
