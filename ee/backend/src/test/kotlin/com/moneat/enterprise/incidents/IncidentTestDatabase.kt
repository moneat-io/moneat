// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupCommands
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupDecisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupEscalations
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupMembers
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteActions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteCommands
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditionGroups
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteConditions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteRevisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteTargets
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRoutes
import com.moneat.enterprise.incidents.models.NativeIncidentAlertEpisodeLinks
import com.moneat.enterprise.incidents.announcements.NativeIncidentAnnouncementRules
import com.moneat.enterprise.incidents.announcements.NativeIncidentAnnouncements
import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentUpdateRequests
import com.moneat.enterprise.incidents.models.NativeIncidentActions
import com.moneat.enterprise.incidents.models.NativeIncidentActionEvents
import com.moneat.enterprise.incidents.models.NativeIncidentCustomFieldOptions
import com.moneat.enterprise.incidents.models.NativeIncidentCustomFields
import com.moneat.enterprise.incidents.models.NativeIncidentFormFields
import com.moneat.enterprise.incidents.models.NativeIncidentForms
import com.moneat.enterprise.incidents.models.NativeIncidentFormSubmissions
import com.moneat.enterprise.incidents.models.NativeIncidentHandovers
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxDeliveries
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.enterprise.incidents.response.NativeIncidentResponseActivations
import com.moneat.enterprise.incidents.response.NativeIncidentResponsePolicies
import com.moneat.enterprise.incidents.response.NativeIncidentResponseTargets
import com.moneat.enterprise.incidents.slack.NativeIncidentSlackChannels
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentTimelineRevisions
import com.moneat.enterprise.incidents.models.NativeIncidentTypes
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.EscalationExecutionEvents
import com.moneat.enterprise.oncall.models.EscalationExecutionStates
import com.moneat.enterprise.oncall.models.EscalationPolicyVersions
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Organizations
import com.moneat.monitor.repositories.ResourceOwnership
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object IncidentTestDatabase {
    private var database: Database? = null

    fun reset() {
        if (database == null) {
            database =
                Database.connect(
                    url = H2_URL,
                    driver = "org.h2.Driver",
                )
        }
        TransactionManager.defaultDatabase = database
        EnterpriseTestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            OrganizationIntegrations,
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
            OnCallAlertTimeline,
            OnCallIncidentAlerts,
            OnCallIncidentTimeline,
            EscalationPolicyVersions,
            EscalationExecutionStates,
            EscalationExecutionEvents,
            NativeIncidentTimelineRevisions,
            NativeIncidentFormSubmissions,
            NativeIncidentAlertEpisodeLinks,
            NativeIncidentSourceLinks,
            NativeIncidentRoleDefinitions,
            NativeIncidentRoleAssignments,
            NativeIncidentParticipants,
            NativeIncidentHandovers,
            NativeIncidentCommands,
            NativeIncidentUpdateRequests,
            NativeIncidentActions,
            NativeIncidentActionEvents,
            NativeIncidentOutboxEvents,
            NativeIncidentOutboxDeliveries,
            NativeIncidentResponsePolicies,
            NativeIncidentResponseActivations,
            NativeIncidentResponseTargets,
            NativeIncidentSlackChannels,
            NativeIncidentAnnouncementRules,
            NativeIncidentAnnouncements,
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

    private const val H2_URL =
        "jdbc:h2:mem:moneat_enterprise_incidents;MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

    fun seedMember(slug: String = "incident-org"): SeededMember =
        transaction {
            val userId =
                Users.insert {
                    it[email] = "$slug@example.test"
                    it[password_hash] = "x"
                    it[name] = "Incident Responder"
                }[Users.id]
            val organizationId =
                Organizations.insert {
                    it[name] = "Incident Organization"
                    it[Organizations.slug] = slug
                }[Organizations.id]
            Memberships.insert {
                it[user_id] = userId
                it[Memberships.organization_id] = organizationId
                it[role] = "owner"
            }
            SeededMember(organizationId, userId)
        }

    fun seedUserInOrganization(organizationId: Int, slug: String): Int =
        transaction {
            val userId =
                Users.insert {
                    it[email] = "$slug@example.test"
                    it[password_hash] = "x"
                    it[name] = "Incident Responder $slug"
                }[Users.id]
            Memberships.insert {
                it[user_id] = userId
                it[Memberships.organization_id] = organizationId
                it[role] = "member"
            }
            userId
        }
}

data class SeededMember(
    val organizationId: Int,
    val userId: Int,
)
