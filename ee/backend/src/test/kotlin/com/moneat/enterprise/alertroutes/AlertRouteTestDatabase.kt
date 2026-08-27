// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteActions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditionGroups
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteCommands
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteRevisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteTargets
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRoutes
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupCommands
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupDecisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupEscalations
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupMembers
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.incidents.models.NativeIncidentAlertEpisodeLinks
import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentCustomFieldOptions
import com.moneat.enterprise.incidents.models.NativeIncidentCustomFields
import com.moneat.enterprise.incidents.models.NativeIncidentFormFields
import com.moneat.enterprise.incidents.models.NativeIncidentForms
import com.moneat.enterprise.incidents.models.NativeIncidentFormSubmissions
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxDeliveries
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.enterprise.incidents.models.NativeIncidentUpdateRequests
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.response.NativeIncidentResponseActivations
import com.moneat.enterprise.incidents.response.NativeIncidentResponsePolicies
import com.moneat.enterprise.incidents.response.NativeIncidentResponseTargets
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentTimelineRevisions
import com.moneat.enterprise.incidents.models.NativeIncidentTypes
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.incident.models.IncidentRoutingRules
import com.moneat.monitor.repositories.ResourceOwnership
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class SeededAlertRouteOrganization(
    val organizationId: Int,
    val adminUserId: Int,
)

data class SeededEscalationPolicy(
    val id: Int,
    val resourceId: Uuid,
)

data class SeededTeam(
    val id: Int,
    val resourceId: Uuid,
    val name: String,
)

object AlertRouteTestDatabase {
    private var database: Database? = null

    fun reset() {
        if (database == null) {
            database = Database.connect(url = H2_URL, driver = "org.h2.Driver")
        }
        TransactionManager.defaultDatabase = database
        EnterpriseTestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            OnCallSchedules,
            EscalationPolicies,
            OrganizationTeams,
            ResourceOwnership,
            AlertEpisodes,
            NativeIncidentTypes,
            NativeIncidentCustomFields,
            NativeIncidentCustomFieldOptions,
            NativeIncidentForms,
            NativeIncidentFormFields,
            OnCallIncidents,
            OnCallAlerts,
            OnCallIncidentAlerts,
            OnCallIncidentTimeline,
            NativeIncidentTimelineRevisions,
            NativeIncidentFormSubmissions,
            NativeIncidentAlertEpisodeLinks,
            NativeIncidentSourceLinks,
            NativeIncidentRoleAssignments,
            NativeIncidentParticipants,
            NativeIncidentCommands,
            NativeIncidentOutboxEvents,
            NativeIncidentOutboxDeliveries,
            NativeIncidentUpdateRequests,
            NativeIncidentResponsePolicies,
            NativeIncidentResponseActivations,
            NativeIncidentResponseTargets,
            IncidentProviderConfigs,
            IncidentRoutingRules,
            IncidentEventLog,
            EnterpriseAlertRoutes,
            EnterpriseAlertRouteConditionGroups,
            EnterpriseAlertRouteConditions,
            EnterpriseAlertRouteActions,
            EnterpriseAlertRouteTargets,
            EnterpriseAlertRouteRevisions,
            EnterpriseAlertRouteCommands,
            EnterpriseAlertGroups,
            EnterpriseAlertGroupMembers,
            EnterpriseAlertGroupDecisions,
            EnterpriseAlertGroupEscalations,
            EnterpriseAlertGroupCommands,
        )
    }

    fun clearReference() {
        TransactionManager.defaultDatabase = null
    }

    fun seedOrganization(slug: String = "alert-routes"): SeededAlertRouteOrganization =
        transaction {
            val userId =
                Users.insert {
                    it[email] = "$slug-admin@example.test"
                    it[password_hash] = "x"
                    it[name] = "Alert Route Admin"
                }[Users.id]
            val organizationId =
                Organizations.insert {
                    it[name] = "Alert Route Organization"
                    it[Organizations.slug] = slug
                }[Organizations.id]
            Memberships.insert {
                it[user_id] = userId
                it[Memberships.organization_id] = organizationId
                it[role] = "owner"
            }
            SeededAlertRouteOrganization(organizationId, userId)
        }

    fun seedUser(organizationId: Int, slug: String, role: String = "member"): Int =
        transaction {
            val userId =
                Users.insert {
                    it[email] = "$slug@example.test"
                    it[password_hash] = "x"
                    it[name] = "Alert Route $slug"
                }[Users.id]
            Memberships.insert {
                it[user_id] = userId
                it[Memberships.organization_id] = organizationId
                it[Memberships.role] = role
            }
            userId
        }

    fun seedEscalationPolicy(organizationId: Int, name: String): SeededEscalationPolicy =
        transaction {
            val resource = Uuid.random()
            val now = Clock.System.now()
            val id =
                EscalationPolicies.insert {
                    it[resourceId] = resource
                    it[EscalationPolicies.organizationId] = organizationId
                    it[EscalationPolicies.name] = name
                    it[repeatCount] = 1
                    it[createdAt] = now
                    it[updatedAt] = now
                }[EscalationPolicies.id].value
            SeededEscalationPolicy(id, resource)
        }

    fun seedTeam(
        organizationId: Int,
        name: String,
        escalationPolicyId: Int? = null,
    ): SeededTeam =
        transaction {
            val resource = Uuid.random()
            val id =
                OrganizationTeams.insert {
                    it[resourceId] = resource
                    it[OrganizationTeams.organizationId] = organizationId
                    it[OrganizationTeams.name] = name
                    it[slug] = name.lowercase().replace(' ', '-')
                    it[OrganizationTeams.escalationPolicyId] = escalationPolicyId
                }[OrganizationTeams.id].value
            SeededTeam(id, resource, name)
        }

    fun seedOwnershipClaim(
        organizationId: Int,
        catalogResourceId: String,
        teamId: Int,
    ) {
        transaction {
            ResourceOwnership.insert {
                it[organization_id] = organizationId
                it[resource_id] = catalogResourceId
                it[team_id] = teamId
                it[updated_by] = "test@example.test"
                it[updated_at] = Clock.System.now()
            }
        }
    }

    fun seedAlertEpisode(
        organizationId: Int,
        source: String,
        deduplicationKey: String,
        priority: String = "P1",
    ): Uuid =
        transaction {
            val resource = Uuid.random()
            val now = Clock.System.now()
            AlertEpisodes.insert {
                it[resourceId] = resource
                it[AlertEpisodes.organizationId] = organizationId
                it[sourceName] = source
                it[AlertEpisodes.deduplicationKey] = deduplicationKey
                it[title] = "Checkout latency"
                it[description] = "Latency above threshold"
                it[AlertEpisodes.priority] = priority
                it[episodeSeq] = 1
                it[episodeKey] = "$deduplicationKey#1"
                it[status] = "OPEN"
                it[openedAt] = now
                it[lastSeenAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
            resource
        }

    private const val H2_URL =
        "jdbc:h2:mem:moneat_enterprise_alert_routes;MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
}
