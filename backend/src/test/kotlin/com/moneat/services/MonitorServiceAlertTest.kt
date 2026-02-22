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

import com.moneat.monitor.models.CreateAlertRequest
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.models.Organizations
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.SystemAlertSettings
import com.moneat.shared.models.SystemAlertTemplateStates
import com.moneat.shared.models.SystemAlerts
import com.moneat.shared.models.Systems
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for MonitorService alert CRUD operations (createAlert, updateAlert, deleteAlert,
 * getAlertConfig, updateAlertScope, listAlerts) - all pure PostgreSQL, no ClickHouse needed.
 */
class MonitorServiceAlertTest {
    private val service = MonitorService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
        private const val ALERT_SCOPE_SYSTEM = "system"
        private const val ALERT_SCOPE_GLOBAL = "global"
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_monitor_alert;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction(db!!) {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    Memberships,
                    Projects,
                    Systems,
                    SystemAlerts,
                    OrganizationAlertTemplates,
                    SystemAlertSettings,
                    SystemAlertTemplateStates,
                    PricingTierConfigs,
                    Subscriptions
                )
            }
        }

        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        transaction {
            SystemAlertTemplateStates.deleteAll()
            SystemAlertSettings.deleteAll()
            SystemAlerts.deleteAll()
            OrganizationAlertTemplates.deleteAll()
            Subscriptions.deleteAll()
            Systems.deleteAll()
            Memberships.deleteAll()
            Projects.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
            PricingTierConfigs.deleteAll()
        }
    }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedFreeTier(): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "FREE"
                it[version] = 1
                it[monthly_unit_limit] = 5000
                it[monthly_error_limit] = 5000
                it[retention_days] = 3
                it[log_retention_days] = 3
                it[max_systems] = 3
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedSystem(orgId: Int, name: String = "test-server"): Pair<UUID, String> {
        val agentKey = "test-key-${UUID.randomUUID()}"
        val (system, returnedKey) = service.createSystem(orgId, name)
        return system.id to returnedKey
    }

    // ==================== createAlert ====================

    @Test
    fun `createAlert creates system-scoped alert`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val request = CreateAlertRequest(
            metric = "cpu_percent",
            condition = ">",
            threshold = 90.0,
            durationSeconds = 300,
            enabled = true
        )
        val alert = service.createAlert(systemId, orgId, request, ALERT_SCOPE_SYSTEM)

        assertEquals("cpu_percent", alert.metric)
        assertEquals(">", alert.condition)
        assertEquals(90.0, alert.threshold)
        assertEquals(300, alert.durationSeconds)
        assertTrue(alert.enabled)
        assertEquals(ALERT_SCOPE_SYSTEM, alert.scope)
        assertNull(alert.lastTriggeredAt)
    }

    @Test
    fun `createAlert creates global-scoped alert`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val request = CreateAlertRequest(
            metric = "mem_percent",
            condition = ">=",
            threshold = 80.0,
            durationSeconds = 60,
            enabled = false
        )
        val alert = service.createAlert(systemId, orgId, request, ALERT_SCOPE_GLOBAL)

        assertEquals("mem_percent", alert.metric)
        assertEquals(ALERT_SCOPE_GLOBAL, alert.scope)
        assertFalse(alert.enabled)
    }

    @Test
    fun `createAlert defaults to system scope`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val request = CreateAlertRequest(metric = "disk_percent", condition = ">", threshold = 95.0)
        val alert = service.createAlert(systemId, orgId, request)
        assertEquals(ALERT_SCOPE_SYSTEM, alert.scope)
    }

    @Test
    fun `createAlert system alert has positive id`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val request = CreateAlertRequest(metric = "load_1", condition = ">", threshold = 2.0)
        val alert = service.createAlert(systemId, orgId, request, ALERT_SCOPE_SYSTEM)
        assertTrue(alert.id > 0)
    }

    // ==================== listAlerts ====================

    @Test
    fun `listAlerts returns seeded default alerts when system is created`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val alerts = service.listAlerts(systemId)
        // createSystem auto-seeds default alerts (cpu, mem, disk, etc.)
        assertTrue(alerts.isNotEmpty())
    }

    @Test
    fun `listAlerts includes custom alerts alongside seeded defaults`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val beforeCount = service.listAlerts(systemId).size
        service.createAlert(systemId, orgId, CreateAlertRequest("cpu_percent", ">", 90.0))
        service.createAlert(systemId, orgId, CreateAlertRequest("mem_percent", ">=", 85.0))

        val alerts = service.listAlerts(systemId)
        assertEquals(beforeCount + 2, alerts.size)
        val metrics = alerts.map { it.metric }.toSet()
        assertTrue(metrics.contains("cpu_percent"))
        assertTrue(metrics.contains("mem_percent"))
    }

    @Test
    fun `listAlerts does not return alerts from other systems`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (system1, _) = seedSystem(orgId, "server-1")
        val (system2, _) = seedSystem(orgId, "server-2")

        val before1 = service.listAlerts(system1).size
        service.createAlert(system1, orgId, CreateAlertRequest("cpu_percent", ">", 90.0))
        service.createAlert(system2, orgId, CreateAlertRequest("mem_percent", ">", 90.0))

        val alerts = service.listAlerts(system1)
        // Only system1's custom alert added; system2's alert not included
        assertEquals(before1 + 1, alerts.size)
        assertTrue(alerts.any { it.metric == "cpu_percent" })
    }

    // ==================== updateAlert ====================

    @Test
    fun `updateAlert returns false for non-existent alert`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val result = service.updateAlert(
            alertId = 99999,
            systemId = systemId,
            organizationId = orgId,
            request = UpdateAlertRequest(threshold = 75.0),
            scope = ALERT_SCOPE_SYSTEM
        )
        assertFalse(result)
    }

    @Test
    fun `updateAlert modifies threshold for system alert`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        val created = service.createAlert(systemId, orgId, CreateAlertRequest("cpu_percent", ">", 90.0))

        val result = service.updateAlert(
            alertId = created.id,
            systemId = systemId,
            organizationId = orgId,
            request = UpdateAlertRequest(threshold = 75.0),
            scope = ALERT_SCOPE_SYSTEM
        )
        assertTrue(result)

        val alerts = service.listAlerts(systemId)
        val updated = alerts.first { it.id == created.id }
        assertEquals(75.0, updated.threshold)
    }

    @Test
    fun `updateAlert modifies enabled flag`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        val created = service.createAlert(systemId, orgId, CreateAlertRequest("mem_percent", ">", 80.0, enabled = true))

        service.updateAlert(
            alertId = created.id,
            systemId = systemId,
            organizationId = orgId,
            request = UpdateAlertRequest(enabled = false),
            scope = ALERT_SCOPE_SYSTEM
        )

        val alerts = service.listAlerts(systemId)
        val updated = alerts.first { it.id == created.id }
        assertFalse(updated.enabled)
    }

    @Test
    fun `updateAlert modifies metric and condition`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        val created = service.createAlert(systemId, orgId, CreateAlertRequest("cpu_percent", ">", 90.0))

        service.updateAlert(
            alertId = created.id,
            systemId = systemId,
            organizationId = orgId,
            request = UpdateAlertRequest(metric = "disk_percent", condition = ">="),
            scope = ALERT_SCOPE_SYSTEM
        )

        val alerts = service.listAlerts(systemId)
        val updated = alerts.first { it.id == created.id }
        assertEquals("disk_percent", updated.metric)
        assertEquals(">=", updated.condition)
    }

    @Test
    fun `updateAlert global-scoped updates organization template`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        val created = service.createAlert(
            systemId,
            orgId,
            CreateAlertRequest("cpu_percent", ">", 90.0),
            ALERT_SCOPE_GLOBAL
        )

        val result = service.updateAlert(
            alertId = created.id,
            systemId = systemId,
            organizationId = orgId,
            request = UpdateAlertRequest(threshold = 85.0),
            scope = ALERT_SCOPE_GLOBAL
        )
        assertTrue(result)
    }

    @Test
    fun `updateAlert returns false when alert belongs to different system`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (system1, _) = seedSystem(orgId, "server-1")
        val (system2, _) = seedSystem(orgId, "server-2")
        val created = service.createAlert(system1, orgId, CreateAlertRequest("cpu_percent", ">", 90.0))

        val result = service.updateAlert(
            alertId = created.id,
            systemId = system2,
            organizationId = orgId,
            request = UpdateAlertRequest(threshold = 50.0),
            scope = ALERT_SCOPE_SYSTEM
        )
        assertFalse(result)

        // Original should be unchanged
        val alerts = service.listAlerts(system1)
        assertEquals(90.0, alerts.first().threshold)
    }

    // ==================== deleteAlert ====================

    @Test
    fun `deleteAlert removes system alert`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        val created = service.createAlert(systemId, orgId, CreateAlertRequest("cpu_percent", ">", 90.0))

        val result = service.deleteAlert(created.id, systemId, orgId, ALERT_SCOPE_SYSTEM)
        assertTrue(result)

        val alerts = service.listAlerts(systemId)
        assertTrue(alerts.none { it.id == created.id })
    }

    @Test
    fun `deleteAlert returns false for non-existent alert`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val result = service.deleteAlert(99999, systemId, orgId, ALERT_SCOPE_SYSTEM)
        assertFalse(result)
    }

    @Test
    fun `deleteAlert returns false when alert belongs to different org`() {
        val org1 = seedOrg("Org 1")
        val org2 = seedOrg("Org 2")
        seedFreeTier()
        val (systemId, _) = seedSystem(org1)
        val created = service.createAlert(systemId, org1, CreateAlertRequest("cpu_percent", ">", 90.0))

        val result = service.deleteAlert(created.id, systemId, org2, ALERT_SCOPE_SYSTEM)
        assertFalse(result)

        // Original alert should still exist (verify by looking for the specific ID)
        assertTrue(service.listAlerts(systemId).any { it.id == created.id })
    }

    @Test
    fun `deleteAlert global-scoped removes organization template`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        val created = service.createAlert(
            systemId,
            orgId,
            CreateAlertRequest("cpu_percent", ">", 90.0),
            ALERT_SCOPE_GLOBAL
        )

        val result = service.deleteAlert(created.id, systemId, orgId, ALERT_SCOPE_GLOBAL)
        assertTrue(result)
    }

    // ==================== getAlertConfig ====================

    @Test
    fun `getAlertConfig returns global scope when system is newly created`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        // createSystem sets ALERT_SCOPE_GLOBAL as the default scope
        val config = service.getAlertConfig(systemId, orgId)
        assertEquals(ALERT_SCOPE_GLOBAL, config.scope)
    }

    @Test
    fun `getAlertConfig returns default alert templates seeded for system`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        val config = service.getAlertConfig(systemId, orgId)
        // Default templates should be seeded
        assertTrue(config.globalAlerts.isNotEmpty() || config.systemAlerts.isNotEmpty())
    }

    @Test
    fun `getAlertConfig shows effective alerts for global scope by default`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        // System scope is GLOBAL by default from createSystem
        service.createAlert(systemId, orgId, CreateAlertRequest("cpu_percent", ">", 90.0), ALERT_SCOPE_GLOBAL)

        val config = service.getAlertConfig(systemId, orgId)
        // When scope is "global", effective = globalAlerts
        assertEquals(ALERT_SCOPE_GLOBAL, config.scope)
        assertEquals(config.globalAlerts.map { it.id }.toSet(), config.effectiveAlerts.map { it.id }.toSet())
    }

    @Test
    fun `getAlertConfig shows effective alerts equals systemAlerts when scope is system`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)
        service.updateAlertScope(systemId, orgId, ALERT_SCOPE_SYSTEM)
        service.createAlert(systemId, orgId, CreateAlertRequest("cpu_percent", ">", 90.0), ALERT_SCOPE_SYSTEM)

        val config = service.getAlertConfig(systemId, orgId)
        assertEquals(ALERT_SCOPE_SYSTEM, config.scope)
        assertEquals(config.systemAlerts.map { it.id }.toSet(), config.effectiveAlerts.map { it.id }.toSet())
    }

    // ==================== updateAlertScope ====================

    @Test
    fun `updateAlertScope returns false for invalid scope`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        assertFalse(service.updateAlertScope(systemId, orgId, "invalid-scope"))
    }

    @Test
    fun `updateAlertScope accepts system scope`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        assertTrue(service.updateAlertScope(systemId, orgId, ALERT_SCOPE_SYSTEM))
        assertEquals(ALERT_SCOPE_SYSTEM, service.getAlertConfig(systemId, orgId).scope)
    }

    @Test
    fun `updateAlertScope accepts global scope`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        assertTrue(service.updateAlertScope(systemId, orgId, ALERT_SCOPE_GLOBAL))
        assertEquals(ALERT_SCOPE_GLOBAL, service.getAlertConfig(systemId, orgId).scope)
    }

    @Test
    fun `updateAlertScope can toggle between system and global`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        service.updateAlertScope(systemId, orgId, ALERT_SCOPE_GLOBAL)
        assertEquals(ALERT_SCOPE_GLOBAL, service.getAlertConfig(systemId, orgId).scope)

        service.updateAlertScope(systemId, orgId, ALERT_SCOPE_SYSTEM)
        assertEquals(ALERT_SCOPE_SYSTEM, service.getAlertConfig(systemId, orgId).scope)
    }

    @Test
    fun `getAlertConfig effective alerts reflects global scope`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (systemId, _) = seedSystem(orgId)

        service.updateAlertScope(systemId, orgId, ALERT_SCOPE_GLOBAL)
        val config = service.getAlertConfig(systemId, orgId)
        // When scope is "global", effective = globalAlerts
        assertEquals(ALERT_SCOPE_GLOBAL, config.scope)
        assertEquals(config.globalAlerts.map { it.id }.toSet(), config.effectiveAlerts.map { it.id }.toSet())
    }
}
