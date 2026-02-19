package com.moneat.services.incident

import com.moneat.models.AlertSource
import com.moneat.models.IncidentEvent
import com.moneat.models.IncidentSeverity
import com.moneat.models.IncidentStatus
import com.moneat.models.ProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

class IncidentIoProviderTest {
    private val event = IncidentEvent(
        title = "Database down",
        description = "Primary database is unavailable",
        severity = IncidentSeverity.HIGH,
        status = IncidentStatus.FIRING,
        source = AlertSource.SYSTEM_DOWN,
        deduplicationKey = "db-down-1",
        organizationId = 1,
        moneatUrl = "https://moneat.test/issues/1"
    )

    @Test
    fun `sendAlert fails fast when alert source config id is missing`() = runBlocking {
        val provider = IncidentIoProvider()
        val config = ProviderConfig(
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
    fun `resolveAlert fails fast when alert source config id is missing`() = runBlocking {
        val provider = IncidentIoProvider()
        val config = ProviderConfig(
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
    fun `testConnection fails fast when alert source config id is missing`() = runBlocking {
        val provider = IncidentIoProvider()
        val config = ProviderConfig(
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
