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

import com.moneat.models.AlertSource
import com.moneat.models.IncidentProviderConfigs
import com.moneat.models.IncidentRoutingRules
import com.moneat.models.IncidentSeverity
import com.moneat.models.Organizations
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class IncidentServiceTest {
    private var providerConfigId: Int = 0

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_incident_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Organizations, IncidentProviderConfigs, IncidentRoutingRules)
            }
            dbInitialized = true
        }

        transaction {
            IncidentRoutingRules.deleteAll()
            IncidentProviderConfigs.deleteAll()
            Organizations.deleteAll()

            val orgId = Organizations.insert {
                it[name] = "Incident Org"
                it[slug] = "incident-org"
            }[Organizations.id]

            providerConfigId = IncidentProviderConfigs.insert {
                it[organizationId] = orgId
                it[providerType] = "incident_io"
                it[name] = "Incident.io"
                it[apiKey] = "key"
                it[configJson] = "{}"
                it[enabled] = true
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }[IncidentProviderConfigs.id].value

            IncidentRoutingRules.insert {
                it[IncidentRoutingRules.providerConfigId] = this@IncidentServiceTest.providerConfigId
                it[alertSource] = AlertSource.SYSTEM_ALERT.name
                it[alertType] = null
                it[incidentSeverity] = "medium"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    @Test
    fun `resolveIncidentSeverity prefers monitor override over routing rule`() {
        val service = IncidentService()

        val severity = service.resolveIncidentSeverity(
            providerConfigId = providerConfigId,
            alertSource = AlertSource.SYSTEM_ALERT,
            monitorSeverityOverride = "critical"
        )

        assertEquals(IncidentSeverity.CRITICAL, severity)
    }

    @Test
    fun `resolveIncidentSeverity falls back to routing rule when override is absent`() {
        val service = IncidentService()

        val severity = service.resolveIncidentSeverity(
            providerConfigId = providerConfigId,
            alertSource = AlertSource.SYSTEM_ALERT,
            monitorSeverityOverride = null
        )

        assertEquals(IncidentSeverity.MEDIUM, severity)
    }

    @Test
    fun `resolveIncidentSeverity returns null for invalid override string`() {
        val service = IncidentService()

        val severity = service.resolveIncidentSeverity(
            providerConfigId = providerConfigId,
            alertSource = AlertSource.SYSTEM_ALERT,
            monitorSeverityOverride = "not-a-severity"
        )

        assertNull(severity)
    }
}
