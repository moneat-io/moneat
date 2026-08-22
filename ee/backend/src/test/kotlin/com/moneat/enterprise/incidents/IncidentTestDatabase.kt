// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents

import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxDeliveries
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
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
            Memberships,
            EscalationPolicies,
            OnCallIncidents,
            OnCallAlerts,
            OnCallIncidentAlerts,
            OnCallIncidentTimeline,
            NativeIncidentCommands,
            NativeIncidentOutboxEvents,
            NativeIncidentOutboxDeliveries,
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
}

data class SeededMember(
    val organizationId: Int,
    val userId: Int,
)
