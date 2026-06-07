package com.moneat.services

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostAlertRepository
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.repositories.HostRepository
import com.moneat.monitor.models.HostData
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonitorServiceTest {
    private val service = MonitorService(HostRepositoryImpl(), HostAlertRepositoryImpl())

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        mockkObject(ClickHouseClient)
        val clickHouseOk = mockk<HttpResponse>()
        every { clickHouseOk.status } returns HttpStatusCode.OK
        every { ClickHouseClient.getDatabase() } returns "testdb"
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

    private fun seedHost(orgId: Int, name: String = "test-server"): Int =
        transaction {
            val now = kotlin.time.Clock.System.now()
            val hostId = Hosts.insert {
                it[organization_id] = orgId
                it[display_name] = name
                it[hostname] = name
                it[status] = "pending"
                it[first_seen_at] = now
                it[last_seen_at] = now
            } get Hosts.id
            HostAlertSettings.insert {
                it[host_id] = hostId
                it[organization_id] = orgId
                it[updated_at] = now
            }
            hostId
        }

    // ──── listHosts ────

    @Test
    fun `listHosts returns all hosts for org`() {
        val orgId = seedOrg()
        seedHost(orgId, "server-1")
        seedHost(orgId, "server-2")

        val hosts = service.listHosts(orgId)
        assertEquals(2, hosts.size)
    }

    @Test
    fun `listHosts returns empty for org with no hosts`() {
        val orgId = seedOrg()
        assertTrue(service.listHosts(orgId).isEmpty())
    }

    @Test
    fun `listHosts does not return hosts from other orgs`() {
        val org1 = seedOrg("Org 1")
        val org2 = seedOrg("Org 2")
        seedHost(org1, "server-1")
        seedHost(org2, "server-2")

        val hosts1 = service.listHosts(org1)
        assertEquals(1, hosts1.size)
        assertEquals("server-1", hosts1[0].displayName)
    }

    // ──── getHostById ────

    @Test
    fun `getHostById returns host when exists`() {
        val orgId = seedOrg()
        val hostId = seedHost(orgId, "my-server")

        val found = service.getHostById(hostId)
        assertNotNull(found)
        assertEquals("my-server", found.displayName)
    }

    @Test
    fun `getHostById returns null for non-existent id`() {
        assertNull(service.getHostById(Int.MAX_VALUE))
    }

    @Test
    fun `getLatestMetrics reads from latest metrics table`() = runBlocking {
        val hostRepository = mockk<HostRepository>()
        val hostAlertRepository = mockk<HostAlertRepository>(relaxed = true)
        val querySlot = slot<String>()
        val now = kotlin.time.Clock.System.now()
        every { hostRepository.getById(7) } returns HostData(
            id = 7,
            organizationId = 42,
            hostname = "web-01",
            displayName = "web-01",
            status = "online",
            lastSeenAt = now,
            agentVersion = null,
            os = null,
            arch = null,
            firstSeenAt = now,
            createdAt = now,
        )
        coEvery { hostRepository.executeClickHouseQuery(capture(querySlot)) } returns """{"data":[]}"""

        MonitorService(hostRepository, hostAlertRepository).getLatestMetrics(7)

        assertTrue(querySlot.captured.contains("metrics_latest_by_host"))
        assertFalse(
            Regex("""FROM\s+`testdb`\.metrics\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(querySlot.captured)
        )
        assertFalse(querySlot.captured.contains("tags['host_id']"))
    }

    // ──── deleteHost ────

    @Test
    fun `deleteHost removes host after clearing alerts`() {
        val orgId = seedOrg()
        val hostId = seedHost(orgId, "to-delete")

        // Clean up dependent rows first (simulates CASCADE which PostgreSQL has in production)
        transaction {
            HostAlertTemplateStates.deleteAll()
            HostAlerts.deleteWhere { HostAlerts.host_id eq hostId }
            HostAlertSettings.deleteWhere { HostAlertSettings.host_id eq hostId }
        }

        assertTrue(runBlocking { service.deleteHost(hostId, orgId) })
        assertNull(service.getHostById(hostId))
    }

    @Test
    fun `deleteHost returns false for wrong org`() {
        val orgId = seedOrg("Org A")
        val otherOrgId = seedOrg("Org B")
        val hostId = seedHost(orgId, "server")

        assertFalse(runBlocking { service.deleteHost(hostId, otherOrgId) })
        // Host should still exist
        assertNotNull(service.getHostById(hostId))
    }

    @Test
    fun `deleteHost returns false for non-existent host`() {
        val orgId = seedOrg()
        assertFalse(runBlocking { service.deleteHost(Int.MAX_VALUE, orgId) })
    }

    // ──── checkHostQuota ────

    @Test
    fun `checkHostQuota returns true when under limit`() {
        val orgId = seedOrg()
        seedFreeTier() // maxHosts = 3

        assertTrue(service.checkHostQuota(orgId))
    }

    @Test
    fun `checkHostQuota returns false when at limit`() {
        val orgId = seedOrg()
        seedFreeTier() // maxHosts = 3
        seedHost(orgId, "s1")
        seedHost(orgId, "s2")
        seedHost(orgId, "s3")

        assertFalse(service.checkHostQuota(orgId))
    }
}
