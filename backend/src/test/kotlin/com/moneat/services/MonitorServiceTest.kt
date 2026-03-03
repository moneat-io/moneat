package com.moneat.services

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Users
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import com.moneat.testsupport.TestDatabaseHelper

class MonitorServiceTest {
    private val service = MonitorService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        mockkObject(ClickHouseClient)
        val clickHouseOk = mockk<HttpResponse>()
        every { clickHouseOk.status } returns HttpStatusCode.OK
        coEvery { ClickHouseClient.execute(any(), any()) } returns clickHouseOk

        mockkObject(EnvConfig.SelfHost)
        every { EnvConfig.SelfHost.enabled } returns false

        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_monitor_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users, Organizations, Memberships, Projects, Subscriptions, Hosts,
            HostAlerts, OrganizationAlertTemplates, HostAlertSettings,
            HostAlertTemplateStates, PricingTierConfigs
        )
    }

    @AfterTest
    fun teardownMocks() {
        unmockkObject(ClickHouseClient)
        unmockkObject(EnvConfig.SelfHost)
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
                it[max_hosts] = 3
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedSystem(
        orgId: Int,
        name: String = "test-server"
    ): Pair<Int, String> {
        val (host, agentKey) = service.createHost(orgId, name)
        return host.id to agentKey
    }

    // --- createSystem ---

    @Test
    fun `createSystem creates system with agent key`() {
        val orgId = seedOrg()
        seedFreeTier()

        val (system, agentKey) = service.createHost(orgId, "web-server")

        assertNotNull(system)
        assertEquals("web-server", system.displayName)
        assertEquals(orgId, system.organizationId)
        assertEquals("pending", system.status)
        assertTrue(agentKey.isNotBlank())
    }

    @Test
    fun `createSystem creates alert settings`() {
        val orgId = seedOrg()
        seedFreeTier()

        val (system, _) = service.createHost(orgId, "web-server")

        val settings =
            transaction {
                HostAlertSettings
                    .selectAll()
                    .where { HostAlertSettings.host_id eq system.id }
                    .firstOrNull()
            }
        assertNotNull(settings)
    }

    // --- validateAgentKey ---

    @Test
    fun `validateAgentKey returns system info for valid key`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (_, agentKey) = service.createHost(orgId, "server")

        val result = service.validateAgentKey(agentKey)
        assertNotNull(result)
        assertEquals(orgId, result.second)
    }

    @Test
    fun `validateAgentKey returns null for invalid key`() {
        assertNull(service.validateAgentKey("invalid-key"))
    }

    // --- listSystems ---

    @Test
    fun `listSystems returns all systems for org`() {
        val orgId = seedOrg()
        seedFreeTier()
        service.createHost(orgId, "server-1")
        service.createHost(orgId, "server-2")

        val systems = service.listHosts(orgId)
        assertEquals(2, systems.size)
    }

    @Test
    fun `listSystems returns empty for org with no systems`() {
        val orgId = seedOrg()
        assertTrue(service.listHosts(orgId).isEmpty())
    }

    @Test
    fun `listSystems does not return systems from other orgs`() {
        val org1 = seedOrg("Org 1")
        val org2 = seedOrg("Org 2")
        seedFreeTier()
        service.createHost(org1, "server-1")
        service.createHost(org2, "server-2")

        val systems1 = service.listHosts(org1)
        assertEquals(1, systems1.size)
        assertEquals("server-1", systems1[0].displayName)
    }

    // --- getSystemById ---

    @Test
    fun `getSystemById returns system when exists`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (system, _) = service.createHost(orgId, "my-server")

        val found = service.getHostById(system.id)
        assertNotNull(found)
        assertEquals("my-server", found.displayName)
    }

    @Test
    fun `getSystemById returns null for non-existent id`() {
        assertNull(service.getHostById(Int.MAX_VALUE))
    }

    // --- deleteSystem ---

    @Test
    fun `deleteSystem removes system after clearing alerts`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (system, _) = service.createHost(orgId, "to-delete")

        // Clean up dependent rows first (simulates CASCADE which PostgreSQL has in production)
        transaction {
            HostAlertTemplateStates.deleteAll()
            HostAlerts.deleteWhere { HostAlerts.host_id eq system.id }
            HostAlertSettings.deleteWhere { HostAlertSettings.host_id eq system.id }
        }

        assertTrue(runBlocking { service.deleteHost(system.id, orgId) })
        assertNull(service.getHostById(system.id))
    }

    @Test
    fun `deleteSystem returns false for wrong org`() {
        val orgId = seedOrg("Org A")
        val otherOrgId = seedOrg("Org B")
        seedFreeTier()
        val (system, _) = service.createHost(orgId, "server")

        assertFalse(runBlocking { service.deleteHost(system.id, otherOrgId) })
        // System should still exist
        assertNotNull(service.getHostById(system.id))
    }

    @Test
    fun `deleteSystem returns false for non-existent system`() {
        val orgId = seedOrg()
        assertFalse(runBlocking { service.deleteHost(Int.MAX_VALUE, orgId) })
    }

    // --- checkSystemQuota ---

    @Test
    fun `checkSystemQuota returns true when under limit`() {
        val orgId = seedOrg()
        seedFreeTier() // maxSystems = 3

        assertTrue(service.checkHostQuota(orgId))
    }

    @Test
    fun `checkSystemQuota returns false when at limit`() {
        val orgId = seedOrg()
        seedFreeTier() // maxSystems = 3
        service.createHost(orgId, "s1")
        service.createHost(orgId, "s2")
        service.createHost(orgId, "s3")

        assertFalse(service.checkHostQuota(orgId))
    }
}
