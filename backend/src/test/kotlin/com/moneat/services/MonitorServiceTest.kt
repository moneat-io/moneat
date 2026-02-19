package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.util.*
import kotlin.test.*
import kotlin.time.Clock

class MonitorServiceTest {
    private val service = MonitorService()

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_monitor_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Organizations, Users, Memberships, Projects,
                    Systems, SystemAlerts, OrganizationAlertTemplates,
                    SystemAlertSettings, SystemAlertTemplateStates,
                    PricingTierConfigs, Subscriptions
                )
            }
            dbInitialized = true
        }

        transaction {
            SystemAlertTemplateStates.deleteAll()
            SystemAlerts.deleteAll()
            SystemAlertSettings.deleteAll()
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

    private fun seedOrg(name: String = "Test Org"): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name.lowercase().replace(" ", "-")
        } get Organizations.id
    }

    private fun seedFreeTier(): Int = transaction {
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
        val keyHash = hashKey(agentKey)
        val systemId = UUID.randomUUID()
        val now = Clock.System.now()

        transaction {
            Systems.insert {
                it[id] = systemId
                it[organization_id] = orgId
                it[Systems.name] = name
                it[host] = null
                it[agent_key_hash] = keyHash
                it[status] = "pending"
                it[last_seen_at] = null
                it[agent_version] = null
                it[os] = null
                it[arch] = null
                it[created_at] = now
                it[updated_at] = now
            }
        }

        return systemId to agentKey
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // --- createSystem ---

    @Test
    fun `createSystem creates system with agent key`() {
        val orgId = seedOrg()
        seedFreeTier()

        val (system, agentKey) = service.createSystem(orgId, "web-server")

        assertNotNull(system)
        assertEquals("web-server", system.name)
        assertEquals(orgId, system.organizationId)
        assertEquals("pending", system.status)
        assertTrue(agentKey.isNotBlank())
    }

    @Test
    fun `createSystem creates alert settings`() {
        val orgId = seedOrg()
        seedFreeTier()

        val (system, _) = service.createSystem(orgId, "web-server")

        val settings = transaction {
            SystemAlertSettings.selectAll()
                .where { SystemAlertSettings.system_id eq system.id }
                .firstOrNull()
        }
        assertNotNull(settings)
    }

    // --- validateAgentKey ---

    @Test
    fun `validateAgentKey returns system info for valid key`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (_, agentKey) = service.createSystem(orgId, "server")

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
        service.createSystem(orgId, "server-1")
        service.createSystem(orgId, "server-2")

        val systems = service.listSystems(orgId)
        assertEquals(2, systems.size)
    }

    @Test
    fun `listSystems returns empty for org with no systems`() {
        val orgId = seedOrg()
        assertTrue(service.listSystems(orgId).isEmpty())
    }

    @Test
    fun `listSystems does not return systems from other orgs`() {
        val org1 = seedOrg("Org 1")
        val org2 = seedOrg("Org 2")
        seedFreeTier()
        service.createSystem(org1, "server-1")
        service.createSystem(org2, "server-2")

        val systems1 = service.listSystems(org1)
        assertEquals(1, systems1.size)
        assertEquals("server-1", systems1[0].name)
    }

    // --- getSystemById ---

    @Test
    fun `getSystemById returns system when exists`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (system, _) = service.createSystem(orgId, "my-server")

        val found = service.getSystemById(system.id)
        assertNotNull(found)
        assertEquals("my-server", found.name)
    }

    @Test
    fun `getSystemById returns null for non-existent id`() {
        assertNull(service.getSystemById(UUID.randomUUID()))
    }

    // --- deleteSystem ---

    @Test
    fun `deleteSystem removes system after clearing alerts`() {
        val orgId = seedOrg()
        seedFreeTier()
        val (system, _) = service.createSystem(orgId, "to-delete")

        // Clean up dependent rows first (simulates CASCADE which PostgreSQL has in production)
        transaction {
            SystemAlertTemplateStates.deleteAll()
            SystemAlerts.deleteWhere { SystemAlerts.system_id eq system.id }
            SystemAlertSettings.deleteWhere { SystemAlertSettings.system_id eq system.id }
        }

        assertTrue(service.deleteSystem(system.id, orgId))
        assertNull(service.getSystemById(system.id))
    }

    @Test
    fun `deleteSystem returns false for wrong org`() {
        val orgId = seedOrg("Org A")
        val otherOrgId = seedOrg("Org B")
        seedFreeTier()
        val (system, _) = service.createSystem(orgId, "server")

        assertFalse(service.deleteSystem(system.id, otherOrgId))
        // System should still exist
        assertNotNull(service.getSystemById(system.id))
    }

    @Test
    fun `deleteSystem returns false for non-existent system`() {
        val orgId = seedOrg()
        assertFalse(service.deleteSystem(UUID.randomUUID(), orgId))
    }

    // --- checkSystemQuota ---

    @Test
    fun `checkSystemQuota returns true when under limit`() {
        val orgId = seedOrg()
        seedFreeTier() // maxSystems = 3

        assertTrue(service.checkSystemQuota(orgId))
    }

    @Test
    fun `checkSystemQuota returns false when at limit`() {
        val orgId = seedOrg()
        seedFreeTier() // maxSystems = 3
        service.createSystem(orgId, "s1")
        service.createSystem(orgId, "s2")
        service.createSystem(orgId, "s3")

        assertFalse(service.checkSystemQuota(orgId))
    }
}
