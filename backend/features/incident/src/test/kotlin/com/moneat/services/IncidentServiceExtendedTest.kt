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
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.incident.models.IncidentRoutingRules
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertStatus
import com.moneat.incident.services.IncidentService
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.EscalationPolicyAlertSources
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.IncidentTestHelper
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.services.WorkflowService
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class IncidentServiceExtendedTest {
    private var orgId: Int = 0
    private var providerConfigId: Int = 0
    private val workflowService: WorkflowService = mockk(relaxed = true)
    private val service = IncidentService(workflowService)
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
                it[alertPriority] = "high"
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
        priority: AlertPriority = AlertPriority.P1,
        status: AlertStatus = AlertStatus.FIRING,
        dedupKey: String = "dedup-1"
    ): AlertLifecycleEvent = AlertLifecycleEvent(
        title = "Test Alert",
        description = "Something went wrong",
        priority = priority,
        status = status,
        source = source,
        deduplicationKey = dedupKey,
        organizationId = orgId,
        moneatUrl = "https://moneat.test/issues/1"
    )

    // ──── fireAlert ────

    @Test
    fun `fireAlert dispatches to registered provider and logs success`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.success("inc-123")
        )

        service.fireAlert(makeEvent())

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])
        assertEquals("inc-123", logs[0][IncidentEventLog.providerIncidentId])
        assertEquals(AlertStatus.FIRING.name, logs[0][IncidentEventLog.incidentStatus])
    }

    @Test
    fun `fireAlert logs failure when provider returns error`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.failure(Exception("API down"))
        )

        service.fireAlert(makeEvent())

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("API down", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `fireAlert logs failure when provider throws exception`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertThrows = RuntimeException("Connection timeout")
        )

        service.fireAlert(makeEvent())

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("Connection timeout", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `fireAlert skips when no routing rule matches source`() = runBlocking {
        IncidentTestHelper.registerMockProvider(testProviderType)

        // HOST_DOWN has no routing rule configured
        service.fireAlert(makeEvent(source = AlertSource.HOST_DOWN))

        assertEquals(0L, IncidentTestHelper.getEventLogCount(orgId))
    }

    @Test
    fun `fireAlert skips when no enabled providers exist`() = runBlocking {
        val emptyOrgId = transaction {
            Organizations.insert {
                it[name] = "Empty Org"
                it[slug] = "empty-org"
            }[Organizations.id]
        }

        service.fireAlert(makeEvent().copy(organizationId = emptyOrgId))

        assertEquals(0L, IncidentTestHelper.getEventLogCount(emptyOrgId))
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
                it[alertPriority] = "high"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.success("ok")
        )

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

    // ──── resolveAlert ────

    @Test
    fun `resolveAlert dispatches to registered provider and logs success`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            resolveAlertResult = Result.success("resolved-1")
        )

        service.resolveAlert(orgId, AlertSource.UPTIME_MONITOR, "dedup-1")

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])
        assertEquals(AlertStatus.RESOLVED.name, logs[0][IncidentEventLog.incidentStatus])
        assertEquals("resolved-1", logs[0][IncidentEventLog.providerIncidentId])
        assertEquals("Alert Resolved", logs[0][IncidentEventLog.title])
    }

    @Test
    fun `resolveAlert logs failure when provider returns error`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            resolveAlertResult = Result.failure(Exception("Resolve failed"))
        )

        service.resolveAlert(orgId, AlertSource.UPTIME_MONITOR, "dedup-2")

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("Resolve failed", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `resolveAlert logs failure when provider throws exception`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            resolveAlertThrows = RuntimeException("Network error")
        )

        service.resolveAlert(orgId, AlertSource.UPTIME_MONITOR, "dedup-3")

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertEquals(false, logs[0][IncidentEventLog.success])
        assertEquals("Network error", logs[0][IncidentEventLog.errorMessage])
    }

    @Test
    fun `resolveAlert skips when no routing rule matches source`() = runBlocking {
        IncidentTestHelper.registerMockProvider(testProviderType)

        service.resolveAlert(orgId, AlertSource.ERROR_ALERT, "dedup-4")

        assertEquals(0L, IncidentTestHelper.getEventLogCount(orgId))
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

        assertEquals(0L, IncidentTestHelper.getEventLogCount(emptyOrgId))
    }

    @Test
    fun `autoResolveAlert resolves allowlisted source`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            resolveAlertResult = Result.success("auto-resolved-1")
        )

        service.autoResolveAlert(orgId, AlertSource.UPTIME_MONITOR, "dedup-auto")

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])
        assertEquals(AlertSource.UPTIME_MONITOR.name, logs[0][IncidentEventLog.alertSource])
        assertEquals(AlertStatus.RESOLVED.name, logs[0][IncidentEventLog.incidentStatus])
        assertEquals("auto-resolved-1", logs[0][IncidentEventLog.providerIncidentId])
    }

    @Test
    fun `autoResolveAlert skips source without deterministic clear signal`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            resolveAlertResult = Result.success("should-not-resolve")
        )
        transaction {
            IncidentRoutingRules.insert {
                it[providerConfigId] = this@IncidentServiceExtendedTest.providerConfigId
                it[alertSource] = AlertSource.ERROR_ALERT.name
                it[alertType] = null
                it[alertPriority] = "high"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        service.autoResolveAlert(orgId, AlertSource.ERROR_ALERT, "dedup-error")

        assertEquals(0L, IncidentTestHelper.getEventLogCount(orgId))
    }

    @Test
    fun `autoResolve source allowlist only includes deterministic recovery sources`() = runBlocking {
        val allowedSources = listOf(
            AlertSource.HOST_ALERT,
            AlertSource.HOST_DOWN,
            AlertSource.UPTIME_MONITOR,
            AlertSource.DASHBOARD_ALERT
        )
        val blockedSource = AlertSource.ERROR_ALERT

        transaction {
            (allowedSources + blockedSource).forEach { source ->
                IncidentRoutingRules.insert {
                    it[providerConfigId] = this@IncidentServiceExtendedTest.providerConfigId
                    it[alertSource] = source.name
                    it[alertType] = null
                    it[alertPriority] = "high"
                    it[createdAt] = Clock.System.now()
                    it[updatedAt] = Clock.System.now()
                }
            }
        }

        IncidentTestHelper.registerMockProvider(
            testProviderType,
            resolveAlertResult = Result.success("auto-resolved")
        )

        allowedSources.forEach { source ->
            service.autoResolveAlert(orgId, source, "dedup-${source.name.lowercase()}")
        }
        service.autoResolveAlert(orgId, blockedSource, "dedup-blocked")

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(allowedSources.size, logs.size)
        assertTrue(logs.all { it[IncidentEventLog.success] })
        assertFalse(logs.any { it[IncidentEventLog.alertSource] == blockedSource.name })
    }

    // ──── resolveAlertPriority edge cases ────

    @Test
    fun `resolveAlertPriority returns null when no routing rule exists for source`() {
        val severity = service.resolveAlertPriority(
            providerConfigId = providerConfigId,
            alertSource = AlertSource.DASHBOARD_ALERT,
            monitorPriorityOverride = null
        )
        assertNull(severity)
    }

    @Test
    fun `resolveAlertPriority returns all severity levels from override`() {
        for (level in AlertPriority.entries) {
            val result = service.resolveAlertPriority(
                providerConfigId = providerConfigId,
                alertSource = AlertSource.UPTIME_MONITOR,
                monitorPriorityOverride = level.name.lowercase()
            )
            assertEquals(level, result)
        }
    }

    @Test
    fun `resolveAlertPriority is case insensitive for override`() {
        val severity = service.resolveAlertPriority(
            providerConfigId = providerConfigId,
            alertSource = AlertSource.UPTIME_MONITOR,
            monitorPriorityOverride = "CrItIcAl"
        )
        assertEquals(AlertPriority.P0, severity)
    }

    // ──── fireAlert with native escalation ────

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
                priority = "P1",
                alertSource = any(),
                deduplicationKey = any(),
                metadata = any()
            )
        } returns "77777777-7777-4777-8777-777777777777"

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

        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.success("inc-esc")
        )

        service.fireAlert(makeEvent())

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs[0][IncidentEventLog.success])

        unmockkObject(FeatureRegistry)
    }

    @Test
    fun `fireAlert skips native escalation when bridge is null`() = runBlocking {
        every { EnvConfig.get("ONCALL_ENABLED", "false") } returns "true"

        mockkObject(FeatureRegistry)
        every { FeatureRegistry.getOnCallBridge() } returns null

        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.success("ok")
        )

        service.fireAlert(makeEvent())

        val logs = IncidentTestHelper.getEventLogs(orgId)
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

        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.success("ok")
        )

        service.fireAlert(makeEvent())

        val logs = IncidentTestHelper.getEventLogs(orgId)
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

        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.success("ok")
        )

        service.fireAlert(makeEvent())

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        assertTrue(logs[0][IncidentEventLog.success])

        unmockkObject(FeatureRegistry)
    }

    // ──── disabled provider ────

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
                it[alertPriority] = "high"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }

            dOrgId
        }

        service.fireAlert(makeEvent().copy(organizationId = disabledOrgId))

        assertEquals(0L, IncidentTestHelper.getEventLogCount(disabledOrgId))
    }

    // ──── event metadata in logs ────

    @Test
    fun `fireAlert preserves event metadata in log entry`() = runBlocking {
        IncidentTestHelper.registerMockProvider(
            testProviderType,
            sendAlertResult = Result.success("meta-1")
        )

        val eventWithMeta = AlertLifecycleEvent(
            title = "Metadata Alert",
            description = "With metadata",
            priority = AlertPriority.P0,
            status = AlertStatus.FIRING,
            source = AlertSource.UPTIME_MONITOR,
            deduplicationKey = "meta-dedup",
            organizationId = orgId,
            metadata = mapOf(
                "host" to kotlinx.serialization.json.JsonPrimitive("db-primary")
            ),
            moneatUrl = "https://moneat.test/issues/2"
        )

        service.fireAlert(eventWithMeta)

        val logs = IncidentTestHelper.getEventLogs(orgId)
        assertEquals(1, logs.size)
        val metadata = logs[0][IncidentEventLog.metadata]
        assertTrue(metadata != null && metadata.contains("db-primary"))
        assertEquals("P0", logs[0][IncidentEventLog.alertPriority])
        assertEquals("meta-dedup", logs[0][IncidentEventLog.deduplicationKey])
    }
}
