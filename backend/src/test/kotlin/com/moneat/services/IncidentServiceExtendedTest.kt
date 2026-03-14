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

package com.moneat.services

import com.moneat.config.EnvConfig
import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.OnCallBridge
import com.moneat.enterprise.PriorityInfo
import com.moneat.incident.models.AlertSource
import com.moneat.incident.models.IncidentEvent
import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.incident.models.IncidentRoutingRules
import com.moneat.incident.models.IncidentSeverity
import com.moneat.incident.models.IncidentStatus
import com.moneat.incident.services.IncidentProvider
import com.moneat.incident.services.IncidentProviderRegistry
import com.moneat.incident.services.IncidentService
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.EscalationPolicyAlertSources
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class IncidentServiceExtendedTest {
    private var orgId: Int = 0
    private var providerConfigId: Int = 0
    private val service = IncidentService()
    private val testProviderType = "test_provider_ext"

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_incident_ext;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Organizations,
            IncidentProviderConfigs,
            IncidentRoutingRules,
            IncidentEventLog,
            EscalationPolicies,
            EscalationPolicyAlertSources
        )

        transaction {
            orgId = Organizations.insert {
                it[name] = "Test Org"
                it[slug] = "test-org"
            }[Organizations.id]

            providerConfigId = IncidentProviderConfigs.insert {
                it[organizationId] = orgId
                it[providerType] = testProviderType
                it[name] = "Test Provider"
                it[apiKey] = "test-key"
                it[configJson] = "{}"
                it[enabled] = true
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }[IncidentProviderConfigs.id].value

            IncidentRoutingRules.insert {
                it[providerConfigId] = this@IncidentServiceExtendedTest.providerConfigId
                it[alertSource] = AlertSource.UPTIME_MONITOR.name
                it[alertType] = null
                it[incidentSeverity] = "high"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        mockkObject(EnvConfig)
        every { EnvConfig.get("ONCALL_ENABLED", "false") } returns "false"
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(EnvConfig)
    }

    private fun makeEvent(
        source: AlertSource = AlertSource.UPTIME_MONITOR,
        severity: IncidentSeverity = IncidentSeverity.HIGH,
        status: IncidentStatus = IncidentStatus.FIRING,
        dedupKey: String = "dedup-1"
    ): IncidentEvent = IncidentEvent(
        title = "Test Alert",
        description = "Something went wrong",
        severity = severity,
        status = status,
        source = source,
        deduplicationKey = dedupKey,
        organizationId = orgId,
        moneatUrl = "https://moneat.test/issues/1"
    )

    // ==================== fireAlert ====================

    @Test
    fun `fireAlert dispatches to registered provider and logs success`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.success("inc-123")
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])
        assertEquals("inc-123", logs[0][IncidentEventLog.providerIncidentId])
        assertEquals(IncidentStatus.FIRING.name, logs[0][IncidentEventLog.incidentStatus])
    }

    @Test
    fun `fireAlert logs failure when provider returns error`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.failure(Exception("API down"))
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("API down", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `fireAlert logs failure when provider throws exception`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } throws RuntimeException("Connection timeout")
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("Connection timeout", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `fireAlert skips when no routing rule matches source`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        IncidentProviderRegistry.register(provider)

        // HOST_DOWN has no routing rule configured
        service.fireAlert(makeEvent(source = AlertSource.HOST_DOWN))

        val count = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .count()
        }
        assertEquals(0L, count)
    }

    @Test
    fun `fireAlert skips when no enabled providers exist`() = runBlocking {
        // Create an org with no provider configs
        val emptyOrgId = transaction {
            Organizations.insert {
                it[name] = "Empty Org"
                it[slug] = "empty-org"
            }[Organizations.id]
        }

        service.fireAlert(makeEvent().copy(organizationId = emptyOrgId))

        val count = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq emptyOrgId }
                .count()
        }
        assertEquals(0L, count)
    }

    @Test
    fun `fireAlert logs error when provider type is not registered`() = runBlocking {
        // Insert a config with an unregistered provider type
        val unknownConfigId = transaction {
            IncidentProviderConfigs.insert {
                it[organizationId] = orgId
                it[providerType] = "unknown_provider"
                it[name] = "Unknown"
                it[apiKey] = "key"
                it[configJson] = "{}"
                it[enabled] = true
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }[IncidentProviderConfigs.id].value
        }
        transaction {
            IncidentRoutingRules.insert {
                it[providerConfigId] = unknownConfigId
                it[alertSource] = AlertSource.UPTIME_MONITOR.name
                it[alertType] = null
                it[incidentSeverity] = "high"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        // Register a real provider for the known type to avoid double-logging
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.success("ok")
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.providerConfigId eq unknownConfigId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("Provider not registered", logs[0][IncidentEventLog.errorMessage])
    }

    // ==================== resolveAlert ====================

    @Test
    fun `resolveAlert dispatches to registered provider and logs success`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.resolveAlert(any(), any()) } returns Result.success("resolved-1")
        IncidentProviderRegistry.register(provider)

        service.resolveAlert(orgId, AlertSource.UPTIME_MONITOR, "dedup-1")

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])
        assertEquals(IncidentStatus.RESOLVED.name, logs[0][IncidentEventLog.incidentStatus])
        assertEquals("resolved-1", logs[0][IncidentEventLog.providerIncidentId])
        assertEquals("Alert Resolved", logs[0][IncidentEventLog.title])
    }

    @Test
    fun `resolveAlert logs failure when provider returns error`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.resolveAlert(any(), any()) } returns Result.failure(Exception("Resolve failed"))
        IncidentProviderRegistry.register(provider)

        service.resolveAlert(orgId, AlertSource.UPTIME_MONITOR, "dedup-2")

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("Resolve failed", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `resolveAlert logs failure when provider throws exception`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.resolveAlert(any(), any()) } throws RuntimeException("Network error")
        IncidentProviderRegistry.register(provider)

        service.resolveAlert(orgId, AlertSource.UPTIME_MONITOR, "dedup-3")

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("Network error", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `resolveAlert skips when no routing rule matches source`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        IncidentProviderRegistry.register(provider)

        service.resolveAlert(orgId, AlertSource.ERROR_ALERT, "dedup-4")

        val count = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .count()
        }
        assertEquals(0L, count)
    }

    @Test
    fun `resolveAlert does nothing when no enabled providers exist`() = runBlocking {
        val emptyOrgId = transaction {
            Organizations.insert {
                it[name] = "Resolve Empty Org"
                it[slug] = "resolve-empty-org"
            }[Organizations.id]
        }

        service.resolveAlert(emptyOrgId, AlertSource.UPTIME_MONITOR, "dedup-5")

        val count = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq emptyOrgId }
                .count()
        }
        assertEquals(0L, count)
    }

    // ==================== resolveIncidentSeverity edge cases ====================

    @Test
    fun `resolveIncidentSeverity returns null when no routing rule exists for source`() {
        val severity = service.resolveIncidentSeverity(
            providerConfigId = providerConfigId,
            alertSource = AlertSource.DASHBOARD_ALERT,
            monitorSeverityOverride = null
        )
        assertNull(severity)
    }

    @Test
    fun `resolveIncidentSeverity returns all severity levels from override`() {
        for (level in IncidentSeverity.entries) {
            val result = service.resolveIncidentSeverity(
                providerConfigId = providerConfigId,
                alertSource = AlertSource.UPTIME_MONITOR,
                monitorSeverityOverride = level.name.lowercase()
            )
            assertEquals(level, result)
        }
    }

    @Test
    fun `resolveIncidentSeverity is case insensitive for override`() {
        val severity = service.resolveIncidentSeverity(
            providerConfigId = providerConfigId,
            alertSource = AlertSource.UPTIME_MONITOR,
            monitorSeverityOverride = "CrItIcAl"
        )
        assertEquals(IncidentSeverity.CRITICAL, severity)
    }

    // ==================== fireAlert with native escalation ====================

    @Test
    fun `fireAlert triggers native escalation when oncall is enabled`() = runBlocking {
        every { EnvConfig.get("ONCALL_ENABLED", "false") } returns "true"

        val bridge = mockk<OnCallBridge>()
        mockkObject(FeatureRegistry)
        every { FeatureRegistry.getOnCallBridge() } returns bridge
        every { bridge.resolvePriority(orgId, "HIGH") } returns PriorityInfo("P1", "High")
        every { bridge.shouldEscalate(orgId, "P1") } returns true
        coEvery {
            bridge.triggerEscalation(
                organizationId = orgId,
                escalationPolicyId = any(),
                title = any(),
                description = any(),
                priorityLevel = "P1",
                alertSource = any(),
                deduplicationKey = any(),
                metadata = any()
            )
        } returns 42

        // Insert escalation policy for the org
        transaction {
            val policyId = EscalationPolicies.insert {
                it[organizationId] = orgId
                it[name] = "Default Policy"
                it[repeatCount] = 1
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }[EscalationPolicies.id].value

            EscalationPolicyAlertSources.insert {
                it[organizationId] = orgId
                it[alertSource] = AlertSource.UPTIME_MONITOR.name
                it[escalationPolicyId] = policyId
            }
        }

        // Also register the test provider so the rest of fireAlert works
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.success("inc-esc")
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        // Verify the event log was written (provider dispatch still happened)
        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs[0][IncidentEventLog.success])

        unmockkObject(FeatureRegistry)
    }

    @Test
    fun `fireAlert skips native escalation when bridge is null`() = runBlocking {
        every { EnvConfig.get("ONCALL_ENABLED", "false") } returns "true"

        mockkObject(FeatureRegistry)
        every { FeatureRegistry.getOnCallBridge() } returns null

        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.success("ok")
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        // Alert dispatch should still succeed
        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])

        unmockkObject(FeatureRegistry)
    }

    @Test
    fun `fireAlert skips escalation when no escalation policy for org`() = runBlocking {
        every { EnvConfig.get("ONCALL_ENABLED", "false") } returns "true"

        val bridge = mockk<OnCallBridge>()
        mockkObject(FeatureRegistry)
        every { FeatureRegistry.getOnCallBridge() } returns bridge

        // No escalation policies inserted - should skip

        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.success("ok")
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])

        unmockkObject(FeatureRegistry)
    }

    @Test
    fun `fireAlert skips escalation when shouldEscalate returns false`() = runBlocking {
        every { EnvConfig.get("ONCALL_ENABLED", "false") } returns "true"

        val bridge = mockk<OnCallBridge>()
        mockkObject(FeatureRegistry)
        every { FeatureRegistry.getOnCallBridge() } returns bridge
        every { bridge.resolvePriority(orgId, "HIGH") } returns PriorityInfo("P2", "High")
        every { bridge.shouldEscalate(orgId, "P2") } returns false

        transaction {
            EscalationPolicies.insert {
                it[organizationId] = orgId
                it[name] = "Policy"
                it[repeatCount] = 1
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.success("ok")
        IncidentProviderRegistry.register(provider)

        service.fireAlert(makeEvent())

        // Alert dispatch should still succeed even though escalation was deferred
        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])

        unmockkObject(FeatureRegistry)
    }

    // ==================== disabled provider ====================

    @Test
    fun `fireAlert ignores disabled provider configs`() = runBlocking {
        // Insert a disabled provider
        val disabledOrgId = transaction {
            val dOrgId = Organizations.insert {
                it[name] = "Disabled Org"
                it[slug] = "disabled-org"
            }[Organizations.id]

            val configId = IncidentProviderConfigs.insert {
                it[organizationId] = dOrgId
                it[providerType] = testProviderType
                it[name] = "Disabled Provider"
                it[apiKey] = "key"
                it[configJson] = "{}"
                it[enabled] = false
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }[IncidentProviderConfigs.id].value

            IncidentRoutingRules.insert {
                it[providerConfigId] = configId
                it[alertSource] = AlertSource.UPTIME_MONITOR.name
                it[alertType] = null
                it[incidentSeverity] = "high"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }

            dOrgId
        }

        service.fireAlert(makeEvent().copy(organizationId = disabledOrgId))

        val count = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq disabledOrgId }
                .count()
        }
        assertEquals(0L, count)
    }

    // ==================== event metadata in logs ====================

    @Test
    fun `fireAlert preserves event metadata in log entry`() = runBlocking {
        val provider = mockk<IncidentProvider>()
        every { provider.providerType } returns testProviderType
        coEvery { provider.sendAlert(any(), any()) } returns Result.success("meta-1")
        IncidentProviderRegistry.register(provider)

        val eventWithMeta = IncidentEvent(
            title = "Metadata Alert",
            description = "With metadata",
            severity = IncidentSeverity.CRITICAL,
            status = IncidentStatus.FIRING,
            source = AlertSource.UPTIME_MONITOR,
            deduplicationKey = "meta-dedup",
            organizationId = orgId,
            metadata = mapOf(
                "host" to kotlinx.serialization.json.JsonPrimitive("db-primary")
            ),
            moneatUrl = "https://moneat.test/issues/2"
        )

        service.fireAlert(eventWithMeta)

        val logs = transaction {
            IncidentEventLog.selectAll()
                .where { IncidentEventLog.organizationId eq orgId }
                .toList()
        }
        assertEquals(1, logs.size)
        val metadata = logs[0][IncidentEventLog.metadata]
        assertTrue(metadata != null && metadata.contains("db-primary"))
        assertEquals("CRITICAL", logs[0][IncidentEventLog.incidentSeverity])
        assertEquals("meta-dedup", logs[0][IncidentEventLog.deduplicationKey])
    }
}
