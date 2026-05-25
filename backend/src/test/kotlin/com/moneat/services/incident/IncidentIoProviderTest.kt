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
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertSeverity
import com.moneat.alerts.models.AlertStatus
import com.moneat.incident.models.ProviderConfig
import com.moneat.incident.services.IncidentIoProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

class IncidentIoProviderTest {
    private val event =
        AlertLifecycleEvent(
            title = "Database down",
            description = "Primary database is unavailable",
            severity = AlertSeverity.HIGH,
            status = AlertStatus.FIRING,
            source = AlertSource.HOST_DOWN,
            deduplicationKey = "db-down-1",
            organizationId = 1,
            moneatUrl = "https://moneat.test/issues/1"
        )

    @Test
    fun `sendAlert fails fast when alert source config id is missing`() =
        runBlocking {
            val provider = IncidentIoProvider()
            val config =
                ProviderConfig(
                    id = 1,
                    organizationId = 1,
                    providerType = "incident_io",
                    name = "incident.io",
                    apiKey = "secret",
                    configJson = buildJsonObject { put("team", "ops") },
                    enabled = true
                )

            val result = provider.sendAlert(event, config)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Missing alert_source_config_id") == true)
        }

    @Test
    fun `resolveAlert fails fast when alert source config id is missing`() =
        runBlocking {
            val provider = IncidentIoProvider()
            val config =
                ProviderConfig(
                    id = 1,
                    organizationId = 1,
                    providerType = "incident_io",
                    name = "incident.io",
                    apiKey = "secret",
                    configJson = buildJsonObject { },
                    enabled = true
                )

            val result = provider.resolveAlert("dedup-1", config)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Missing alert_source_config_id") == true)
        }

    @Test
    fun `testConnection fails fast when alert source config id is missing`() =
        runBlocking {
            val provider = IncidentIoProvider()
            val config =
                ProviderConfig(
                    id = 1,
                    organizationId = 1,
                    providerType = "incident_io",
                    name = "incident.io",
                    apiKey = "secret",
                    configJson = buildJsonObject { },
                    enabled = true
                )

            val result = provider.testConnection(config)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Missing alert_source_config_id") == true)
        }
}
