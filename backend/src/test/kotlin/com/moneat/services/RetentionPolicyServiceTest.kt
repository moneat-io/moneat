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

import com.moneat.models.Organizations
import com.moneat.models.PricingTierConfigs
import com.moneat.models.Projects
import com.moneat.models.Subscriptions
import com.moneat.models.Systems
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class RetentionPolicyServiceTest {
    private val service = RetentionPolicyService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db =
                Database.connect(
                    url =
                    "jdbc:h2:mem:moneat_retention_policy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver"
                )
            transaction(db!!) {
                SchemaUtils.create(
                    Organizations,
                    Projects,
                    Systems,
                    PricingTierConfigs,
                    Subscriptions
                )
            }
        }

        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        transaction {
            Subscriptions.deleteAll()
            Systems.deleteAll()
            Projects.deleteAll()
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

    private fun seedFreeTier(retentionDays: Int = 3, logRetentionDays: Int = 3): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "FREE"
                it[version] = 1
                it[monthly_unit_limit] = 5000
                it[monthly_error_limit] = 5000
                it[retention_days] = retentionDays
                it[log_retention_days] = logRetentionDays
                it[max_systems] = 3
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[yearly_price_cents] = 0
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedProTier(retentionDays: Int = 30, logRetentionDays: Int = 30): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "PRO"
                it[version] = 1
                it[monthly_unit_limit] = 50_000
                it[monthly_error_limit] = 50_000
                it[retention_days] = retentionDays
                it[log_retention_days] = logRetentionDays
                it[max_systems] = 10
                it[monitor_interval_seconds] = 30
                it[monthly_price_cents] = 2900
                it[yearly_price_cents] = 24900
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedProject(orgId: Int, name: String = "test-project"): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[Projects.slug] = name
            } get Projects.id
        }

    private fun seedSystem(orgId: Int, name: String = "test-server"): UUID {
        val systemId = UUID.randomUUID()
        val keyHash = MessageDigest.getInstance(
            "SHA-256"
        ).digest("key".toByteArray()).joinToString("") { "%02x".format(it) }
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
        return systemId
    }

    private fun seedSubscription(orgId: Int, tierId: Int, plan: String = "PRO"): Int =
        transaction {
            Subscriptions.insert {
                it[Subscriptions.organization_id] = orgId
                it[Subscriptions.plan] = plan
                it[Subscriptions.status] = "active"
                it[Subscriptions.pricing_tier_config_id] = tierId
            } get Subscriptions.id
        }

    @Test
    fun `getRetentionDaysForOrganization returns FREE tier retention when no subscription`() =
        runBlocking {
            seedFreeTier(retentionDays = 3)
            val orgId = seedOrg()

            val days = service.getRetentionDaysForOrganization(orgId)

            assertEquals(3, days)
        }

    @Test
    fun `getRetentionDaysForOrganization returns PRO tier retention when has subscription`() =
        runBlocking {
            val freeTierId = seedFreeTier()
            val proTierId = seedProTier(retentionDays = 30)
            val orgId = seedOrg()
            seedSubscription(orgId, proTierId, "PRO")

            val days = service.getRetentionDaysForOrganization(orgId)

            assertEquals(30, days)
        }

    @Test
    fun `getRetentionDaysForProject returns org retention when project exists`() =
        runBlocking {
            val proTierId = seedProTier(retentionDays = 14)
            val orgId = seedOrg()
            seedSubscription(orgId, proTierId)
            val projectId = seedProject(orgId)

            val days = service.getRetentionDaysForProject(projectId)

            assertEquals(14, days)
        }

    @Test
    fun `getRetentionDaysForProject returns null when project not found`() =
        runBlocking {
            val days = service.getRetentionDaysForProject(99_999L)

            assertNull(days)
        }

    @Test
    fun `getRetentionDaysForSystem returns org retention when system exists`() =
        runBlocking {
            val proTierId = seedProTier(retentionDays = 7)
            val orgId = seedOrg()
            seedSubscription(orgId, proTierId)
            val systemId = seedSystem(orgId)

            val days = service.getRetentionDaysForSystem(systemId)

            assertEquals(7, days)
        }

    @Test
    fun `getRetentionDaysForSystem returns null when system not found`() =
        runBlocking {
            val days = service.getRetentionDaysForSystem(UUID.randomUUID())

            assertNull(days)
        }

    @Test
    fun `getRetentionDaysByOrganization returns map of org to retention`() =
        runBlocking {
            seedFreeTier()
            val proTierId = seedProTier(retentionDays = 30)
            val org1 = seedOrg("Org 1")
            val org2 = seedOrg("Org 2")
            seedSubscription(org2, proTierId)

            val map = service.getRetentionDaysByOrganization()

            assertEquals(2, map.size)
            assertEquals(3, map[org1])
            assertEquals(30, map[org2])
        }

    @Test
    fun `getRetentionDaysByOrganization returns empty map when no orgs`() =
        runBlocking {
            seedFreeTier()

            val map = service.getRetentionDaysByOrganization()

            assertEquals(0, map.size)
        }

    @Test
    fun `getLogRetentionDaysForOrganization returns tier log retention`() =
        runBlocking {
            seedFreeTier(logRetentionDays = 7)
            val orgId = seedOrg()

            val days = service.getLogRetentionDaysForOrganization(orgId)

            assertEquals(7, days)
        }

    @Test
    fun `getLogRetentionDaysForProject returns org log retention when project exists`() =
        runBlocking {
            seedProTier(logRetentionDays = 14)
            val orgId = seedOrg()
            val proTierId = seedProTier(logRetentionDays = 14)
            seedSubscription(orgId, proTierId)
            val projectId = seedProject(orgId)

            val days = service.getLogRetentionDaysForProject(projectId)

            assertEquals(14, days)
        }

    @Test
    fun `getLogRetentionDaysByOrganization returns map of org to log retention`() =
        runBlocking {
            seedFreeTier(logRetentionDays = 3)
            val proTierId = seedProTier(logRetentionDays = 30)
            val org1 = seedOrg("Org 1")
            val org2 = seedOrg("Org 2")
            seedSubscription(org2, proTierId)

            val map = service.getLogRetentionDaysByOrganization()

            assertEquals(2, map.size)
            assertEquals(3, map[org1])
            assertEquals(30, map[org2])
        }
}
