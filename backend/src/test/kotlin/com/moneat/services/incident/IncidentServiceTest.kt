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

package com.moneat.services.incident

import com.moneat.alerts.models.AlertSource
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.incident.models.IncidentRoutingRules
import com.moneat.alerts.models.AlertSeverity
import com.moneat.incident.services.IncidentService
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class IncidentServiceTest {
    private var providerConfigId: Int = 0

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_incident_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Organizations, IncidentProviderConfigs, IncidentRoutingRules)
        transaction {
            val orgId =
                Organizations.insert {
                    it[name] = "Incident Org"
                    it[slug] = "incident-org"
                }[Organizations.id]

            providerConfigId =
                IncidentProviderConfigs
                    .insert {
                        it[organizationId] = orgId
                        it[providerType] = "incident_io"
                        it[name] = "Incident.io"
                        it[apiKey] = "key"
                        it[configJson] = "{}"
                        it[enabled] = true
                        it[createdAt] = Clock.System.now()
                        it[updatedAt] = Clock.System.now()
                    }[IncidentProviderConfigs.id]
                    .value

            IncidentRoutingRules.insert {
                it[IncidentRoutingRules.providerConfigId] = this@IncidentServiceTest.providerConfigId
                it[alertSource] = AlertSource.HOST_ALERT.name
                it[alertType] = null
                it[incidentSeverity] = "medium"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    @Test
    fun `resolveAlertSeverity prefers monitor override over routing rule`() {
        val service = IncidentService()

        val severity =
            service.resolveAlertSeverity(
                providerConfigId = providerConfigId,
                alertSource = AlertSource.HOST_ALERT,
                monitorSeverityOverride = "critical"
            )

        assertEquals(AlertSeverity.CRITICAL, severity)
    }

    @Test
    fun `resolveAlertSeverity falls back to routing rule when override is absent`() {
        val service = IncidentService()

        val severity =
            service.resolveAlertSeverity(
                providerConfigId = providerConfigId,
                alertSource = AlertSource.HOST_ALERT,
                monitorSeverityOverride = null
            )

        assertEquals(AlertSeverity.MEDIUM, severity)
    }

    @Test
    fun `resolveAlertSeverity returns null for invalid override string`() {
        val service = IncidentService()

        val severity =
            service.resolveAlertSeverity(
                providerConfigId = providerConfigId,
                alertSource = AlertSource.HOST_ALERT,
                monitorSeverityOverride = "not-a-severity"
            )

        assertNull(severity)
    }
}
